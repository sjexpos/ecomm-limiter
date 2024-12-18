package io.oigres.ecomm.service.limiter.mps.reader;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import io.oigres.ecomm.service.limiter.config.MessageReaderProperties;
import io.oigres.ecomm.service.limiter.mps.KafkaMessage;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.env.Environment;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
//@Observed
public class MessageReader {
    private final PendingQueue<KafkaMessage> pendingQueue;
    private final PendingQueue<KafkaMessage>.OrderIterator orderIterator;
    private final ConcurrentHashMap<String,Object> inflightSet;
    private final Duration offerTimeout;
    private final Duration maxPurgeTime;

    public MessageReader(
            MessageReaderProperties configuration,
            MeterRegistry registry,
            Environment env
    ) {
        this.pendingQueue = new PendingQueue<KafkaMessage>(configuration.getQueueSize());
        this.orderIterator = (PendingQueue<KafkaMessage>.OrderIterator) this.pendingQueue.orderIterator();
        this.inflightSet = new ConcurrentHashMap<>();
        this.offerTimeout = configuration.getQueueTimeout();
        this.maxPurgeTime = configuration.getPurgeTime();
        Gauge.builder("mps.pending", this.pendingQueue, PendingQueue::size)
                .description("Kafka messages to be processed")
                .tag("region", "us-east")
                .baseUnit("messages")
                .register(registry);

    }

    @Observed(name = "mps.messages.queue")
    public Object queue(ConsumerRecord<String, Object> record, Acknowledgment ack) throws InterruptedException {
        boolean added = false;
        while (!added) {
            added = this.pendingQueue.offer(
                    new KafkaMessage(record, ack),
                    this.offerTimeout
            );
            if (!added && this.pendingQueue.remainingCapacity() == 0) {
                this.pendingQueue.purge(KafkaMessage::isAcknowledged, this.maxPurgeTime);
            }
        }
        return null;
    }

    @Observed(name = "mps.messages.poll")
    public KafkaMessage poll(Duration timeout) throws InterruptedException {
        KafkaMessage msg = this.orderIterator.next(timeout);
        if (msg != null) {
            Scope scope = msg.getContext().makeCurrent(); // Opentelemetry propagation
            if (msg.getRecord() != null && msg.getRecord().key() != null) {
                this.inflightSet.put(msg.getRecord().key(), msg);
            } else {
                log.warn("Trying to process e message without record or key!");
            }
        }
        return msg;
    }

    @Observed(name = "mps.messages.acknowledge")
    public Object acknowledgeIfPossible(KafkaMessage message) {
        final AtomicBoolean wasPrevAcknowledged = new AtomicBoolean(true);
        this.pendingQueue.forEach(m -> {
                    if (m.getRecord().partition() != message.getRecord().partition()) {
                        return;
                    }
                    if (wasPrevAcknowledged.get()) {
                        m.acknowledge();
                    }
                    wasPrevAcknowledged.set(m.isAcknowledged());
                },
                m -> (m.getRecord().partition() == message.getRecord().partition()) && !m.isAcknowledged());
        return null;
    }

}
