package com.ksenterprise.org.spark.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


@Slf4j
public class KafkaConsumer {

    public static void main(String[] args) {

        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers","localhost:9092,anotherhost:9092");
        kafkaParams.put("key.deserializer",StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", "NumbersGroup");
        kafkaParams.put("auto.offset.reset","latest");
        kafkaParams.put("enable.auto.commit",false);

        SparkConf sparkConf = new SparkConf().setAppName("NumberStream").setMaster("localhost:7077");
        sparkConf.set("spark.streaming.backpressure.enable", "true");
        sparkConf.set("spark.streaming.kafka.maxRatePerPartition", "10");
        sparkConf.set("spark.serializer", "org.apache.spark.serializer.KyroSerializer");

        sparkConf.registerKryoClasses((Class<?>[]) Collections.singletonList(ConsumerRecord.class).toArray());


        try (JavaStreamingContext jsc = new JavaStreamingContext(sparkConf, Durations.milliseconds(500))) {

            Map<TopicPartition, Long> offSetMap = new HashMap<>();
            offSetMap.put(new TopicPartition("numbers", 0), 0L);

            final AtomicReference<OffsetRange[]> offsetRanges = new AtomicReference<>();

            JavaInputDStream<ConsumerRecord<String, String>> stream = KafkaUtils.createDirectStream(jsc, LocationStrategies.PreferConsistent(),ConsumerStrategies.Assign(offSetMap.keySet(), kafkaParams, offSetMap));
            // Transform
            stream.transform((Function<JavaRDD<ConsumerRecord<String, String>>, JavaRDD<ConsumerRecord<String, String>>>) rdd -> {
                OffsetRange[] offSets = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                offsetRanges.set(offSets);
                return rdd;
            }).foreachRDD((VoidFunction<JavaRDD<ConsumerRecord<String, String>>>) consumerRecordJavaRDD -> {

                List<ConsumerRecord<String, String>> collect = consumerRecordJavaRDD.collect();
                if (collect != null && !collect.isEmpty()) {
                    collect.forEach(consumerRecord ->
                            log.info(String.format("Topic %s produced message : %s", consumerRecord.topic(), consumerRecord.value())));
                }
            });
            jsc.start();
            jsc.awaitTermination();
        } catch (InterruptedException e) {
            log.error(e.getMessage(),e);
        }
    }
}
