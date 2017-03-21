/*
 * Copyright Amherst College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.amherst.acdc.trellis.rosid.file;

import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_DELETE;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_EVENT;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_INBOUND_ADD;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_INBOUND_DELETE;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_LDP_CONTAINER_ADD;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_LDP_CONTAINER_DELETE;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_RECACHE;
import static edu.amherst.acdc.trellis.rosid.common.Constants.TOPIC_UPDATE;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.cacheWriter;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.deleter;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.inboundAdd;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.inboundDelete;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.ldpAdder;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.ldpDeleter;
import static edu.amherst.acdc.trellis.rosid.file.StreamProcessing.updater;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.System.getProperty;
import static org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.streams.kstream.TimeWindows.of;

import edu.amherst.acdc.trellis.rosid.common.DatasetSerde;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.rdf.api.Dataset;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.processor.StateStoreSupplier;
import org.apache.kafka.streams.state.Stores;

/**
 * @author acoburn
 */
final class StreamConfiguration {

    private static final Long WINDOW_SIZE = parseLong(getProperty("kafka.window.delay.ms", "5000"));

    // 2^12-1
    private static final Integer CACHE_SIZE = parseInt(getProperty("kafka.window.cache.size", "4095"));

    private static final String CACHE_NAME = "trellis.caching";

    /**
     * Configure the KafkaStream processor
     * @param storage the storage configuration
     * @return the configured kafka stream processor
     */
    public static KafkaStreams configure(final Map<String, String> storage) {
        final String bootstrapServers = System.getProperty("kafka.bootstrap.servers");

        final Map<String, Object> props = new HashMap<>();
        props.put(APPLICATION_ID_CONFIG, "trellis-repository-application");
        props.put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        final StateStoreSupplier cachingStore = Stores.create(CACHE_NAME).withStringKeys().withStringValues()
            .inMemory().maxEntries(CACHE_SIZE).build();

        final Serde<String> kserde = new Serdes.StringSerde();
        final Serde<Dataset> vserde = new DatasetSerde();

        final KStreamBuilder builder = new KStreamBuilder();
        builder.addStateStore(cachingStore);

        // TODO -- rework to accomodate the consolidation of topic_delete into topic_update
        // and be sure to skip the cache delay for "primary" changes.
        @SuppressWarnings("unchecked")
        final KStream<String, Dataset>[] updates = builder.stream(kserde, vserde, TOPIC_UPDATE)
            .flatMap((k, v) -> updater(storage, k, v))
            .branch(StreamProcessing.isNew, StreamProcessing.hasInboundRefs, StreamProcessing.otherwise);
        updates[0].to(TOPIC_LDP_CONTAINER_ADD);
        updates[1].to(TOPIC_INBOUND_ADD);
        updates[2].to(TOPIC_RECACHE);

        @SuppressWarnings("unchecked")
        final KStream<String, Dataset>[] deletes = builder.stream(kserde, vserde, TOPIC_DELETE)
            .flatMap((k, v) -> deleter(storage, k, v))
            .branch(StreamProcessing.isDeleteParent, StreamProcessing.isDeleteTarget, StreamProcessing.otherwise);
        deletes[0].to(TOPIC_LDP_CONTAINER_DELETE);
        deletes[1].to(TOPIC_INBOUND_DELETE);
        deletes[2].to(TOPIC_EVENT);
        deletes[3].to(TOPIC_DELETE);

        builder.stream(kserde, vserde, TOPIC_INBOUND_ADD)
            .foreach((k, v) -> inboundAdd(storage, k, v));

        builder.stream(kserde, vserde, TOPIC_INBOUND_DELETE)
            .foreach((k, v) -> inboundDelete(storage, k, v));

        builder.stream(kserde, vserde, TOPIC_LDP_CONTAINER_ADD)
            .map((k, v) -> ldpAdder(storage, k, v))
            .to(TOPIC_RECACHE);

        builder.stream(kserde, vserde, TOPIC_LDP_CONTAINER_DELETE)
            .map((k, v) -> ldpDeleter(storage, k, v))
            .to(TOPIC_RECACHE);

        builder.stream(kserde, vserde, TOPIC_RECACHE).groupByKey()
            .reduce((val1, val2) -> val1, of(WINDOW_SIZE), CACHE_NAME)
            .toStream((k, v) -> k.key())
            .map((k, v) -> cacheWriter(storage, k, v))
            .to(TOPIC_EVENT);

        return new KafkaStreams(builder, new StreamsConfig(props));
    }


    private StreamConfiguration() {
        //prevent instantiation
    }
}