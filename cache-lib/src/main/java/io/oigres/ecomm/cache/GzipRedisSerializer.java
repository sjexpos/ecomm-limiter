package io.oigres.ecomm.cache;

import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This redis serializer compress the data using gzip.
 *
 * @author sergio.exposito (sjexpos@gmail.com)
 */
@Slf4j
public class GzipRedisSerializer<T> implements RedisSerializer<T> {

    private RedisSerializer<T> innerSerializer;

    public GzipRedisSerializer(RedisSerializer<T> innerSerializer) {
        this.innerSerializer = innerSerializer;
    }

    public static final int BUFFER_SIZE = 4096;

    @Override
    public byte[] serialize(T graph) throws SerializationException {

        if (GzipRedisSerializer.log.isTraceEnabled())
            GzipRedisSerializer.log.trace("Serializing data to gzip");

        if (graph == null)
            return new byte[0];

        try {
            byte[] bytes = innerSerializer.serialize(graph);
            @Cleanup ByteArrayOutputStream bos = new ByteArrayOutputStream();
            @Cleanup GZIPOutputStream gzip = new GZIPOutputStream(bos);

            gzip.write(bytes);
            gzip.close();
            byte[] result = bos.toByteArray();

            if (GzipRedisSerializer.log.isTraceEnabled())
                GzipRedisSerializer.log.trace("Data size {} was compressed to {} bytes.", bytes.length, result.length);

            return result;
        } catch (Exception e) {
            throw new SerializationException("Gzip error", e);
        }
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {

        if (GzipRedisSerializer.log.isTraceEnabled())
            GzipRedisSerializer.log.trace("Deserializing data");

        if (bytes == null || bytes.length == 0)
            return null;

        try {
            @Cleanup ByteArrayOutputStream bos = new ByteArrayOutputStream();
            @Cleanup ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            @Cleanup GZIPInputStream gzip = new GZIPInputStream(bis);

            byte[] buff = new byte[BUFFER_SIZE];
            int n;
            while ((n = gzip.read(buff, 0, BUFFER_SIZE)) > 0) {
                bos.write(buff, 0, n);
            }
            T result = (T) innerSerializer.deserialize(bos.toByteArray());

            if (GzipRedisSerializer.log.isTraceEnabled())
                GzipRedisSerializer.log.trace("result=[{}]", result);

            return result;
        } catch (Exception e) {
            throw new SerializationException("Gzip error", e);
        }
    }
}