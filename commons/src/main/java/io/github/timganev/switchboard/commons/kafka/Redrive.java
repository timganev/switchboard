package io.github.timganev.switchboard.commons.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Lists what a consumer parked and sends it back through the consumer's own redrive topic, never the shared one.
 */
public class Redrive {

    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final ConsumerFactory<?, ?> consumerFactory;
    private final KafkaTemplate<Object, Object> bytesTemplate;
    private final String consumer;

    public Redrive(ConsumerFactory<?, ?> consumerFactory, KafkaTemplate<Object, Object> template, String consumer) {
        this.consumerFactory = consumerFactory;
        this.bytesTemplate = new KafkaTemplate<>(template.getProducerFactory().copyWithConfigurationOverride(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        this.consumer = consumer;
    }

    public synchronized List<Parked> parked(int limit) {
        List<Parked> parked = new ArrayList<>();
        try (Consumer<byte[], byte[]> reader = reader()) {
            readUnredriven(reader, record -> {
                if (parked.size() < limit) {
                    parked.add(Parked.of(record));
                }
            });
        }
        return parked;
    }

    public synchronized int redrive() {
        List<CompletableFuture<?>> sent = new ArrayList<>();
        try (Consumer<byte[], byte[]> reader = reader()) {
            Map<TopicPartition, OffsetAndMetadata> read = readUnredriven(reader, record -> sent.add(bytesTemplate.send(redriven(record))));
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).join();
            reader.commitSync(read);
        }
        return sent.size();
    }

    // The parking headers are dropped, so a record that fails again is parked with fresh ones.
    private ProducerRecord<Object, Object> redriven(ConsumerRecord<byte[], byte[]> record) {
        ProducerRecord<Object, Object> redriven = new ProducerRecord<>(DeadLetters.redriveTopic(consumer), record.key(), record.value());
        for (Header header : record.headers()) {
            if (!header.key().startsWith(KafkaHeaders.PREFIX + "dlt-") && !header.key().equals(DeadLetters.REASON_HEADER)) {
                redriven.headers().add(header);
            }
        }
        return redriven;
    }

    private Map<TopicPartition, OffsetAndMetadata> readUnredriven(Consumer<byte[], byte[]> reader,
                                                                  java.util.function.Consumer<ConsumerRecord<byte[], byte[]>> handler) {
        List<TopicPartition> partitions = reader.partitionsFor(DeadLetters.topic(consumer)).stream()
                .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                .toList();
        if (partitions.isEmpty()) {
            return Map.of();
        }
        reader.assign(partitions);
        Map<TopicPartition, OffsetAndMetadata> committed = reader.committed(Set.copyOf(partitions));
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            if (offset == null) {
                reader.seekToBeginning(List.of(partition));
            } else {
                reader.seek(partition, offset.offset());
            }
        }
        Map<TopicPartition, Long> ends = reader.endOffsets(partitions);
        Instant deadline = Instant.now().plus(READ_TIMEOUT);
        while (partitions.stream().anyMatch(partition -> reader.position(partition) < ends.get(partition))) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Could not read " + DeadLetters.topic(consumer) + " within " + READ_TIMEOUT);
            }
            for (ConsumerRecord<byte[], byte[]> record : reader.poll(POLL)) {
                if (record.offset() < ends.get(new TopicPartition(record.topic(), record.partition()))) {
                    handler.accept(record);
                }
            }
        }
        Map<TopicPartition, OffsetAndMetadata> read = new HashMap<>();
        ends.forEach((partition, end) -> read.put(partition, new OffsetAndMetadata(end)));
        return read;
    }

    @SuppressWarnings("unchecked")
    private Consumer<byte[], byte[]> reader() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return (Consumer<byte[], byte[]>) consumerFactory.createConsumer(
                DeadLetters.redriveTopic(consumer), null, null, overrides);
    }

    /**
     * Where a parked record came from and why it failed; the value itself is left out.
     */
    public record Parked(String topic, int partition, long offset, String key, String reason, Instant parkedAt) {

        static Parked of(ConsumerRecord<byte[], byte[]> record) {
            byte[] partition = header(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
            byte[] offset = header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
            return new Parked(text(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)),
                    partition == null ? -1 : ByteBuffer.wrap(partition).getInt(),
                    offset == null ? -1 : ByteBuffer.wrap(offset).getLong(),
                    text(record.key()),
                    text(header(record, DeadLetters.REASON_HEADER)),
                    Instant.ofEpochMilli(record.timestamp()));
        }

        private static byte[] header(ConsumerRecord<byte[], byte[]> record, String key) {
            Header header = record.headers().lastHeader(key);
            return header == null ? null : header.value();
        }

        private static String text(byte[] bytes) {
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
