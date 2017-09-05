/*
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
package org.trellisldp.rosid.file;

import static java.net.URI.create;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.lines;
import static java.nio.file.Files.walk;
import static java.time.Instant.now;
import static java.util.Objects.isNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Stream.empty;
import static java.util.stream.Stream.of;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.rosid.file.Constants.MEMENTO_CACHE;
import static org.trellisldp.rosid.file.Constants.RESOURCE_CACHE;
import static org.trellisldp.rosid.file.Constants.RESOURCE_JOURNAL;
import static org.trellisldp.rosid.file.Constants.RESOURCE_QUADS;
import static org.trellisldp.rosid.file.FileUtils.resourceDirectory;
import static org.trellisldp.spi.RDFUtils.TRELLIS_PREFIX;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Quad;
import org.apache.commons.rdf.api.Triple;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.trellisldp.api.Resource;
import org.trellisldp.rosid.common.AbstractResourceService;
import org.trellisldp.spi.EventService;
import org.trellisldp.vocabulary.ACL;
import org.trellisldp.vocabulary.AS;
import org.trellisldp.vocabulary.DC;
import org.trellisldp.vocabulary.FOAF;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.PROV;
import org.trellisldp.vocabulary.RDF;
import org.trellisldp.vocabulary.Trellis;
import org.trellisldp.vocabulary.XSD;

/**
 * @author acoburn
 */
public class FileResourceService extends AbstractResourceService {

    private static final Logger LOGGER = getLogger(FileResourceService.class);

    /**
     * Create a File-based repository service
     * @param partitions the partition configuration
     * @param curator the curator framework
     * @param producer the kafka producer
     * @param notifications the notification service
     * @param idSupplier an identifier supplier for new resources
     * @param async generate cached resources asynchronously if true, synchonously if false
     * @throws IOException if the directory is not writable
     */
    public FileResourceService(final Map<String, String> partitions, final CuratorFramework curator,
            final Producer<String, String> producer, final EventService notifications,
            final Supplier<String> idSupplier, final Boolean async) throws IOException {
        super(partitions, producer, curator, notifications, idSupplier, async);

        init();
    }

    @Override
    public Optional<Resource> get(final IRI identifier) {
        return ofNullable(resourceDirectory(partitions, identifier)).filter(File::exists)
            .flatMap(dir -> new File(dir, RESOURCE_CACHE).exists() ?
                    CachedResource.find(dir, identifier) : VersionedResource.find(dir, identifier, now()));
    }

    @Override
    public Optional<Resource> get(final IRI identifier, final Instant time) {
        return ofNullable(resourceDirectory(partitions, identifier)).filter(File::exists)
            .flatMap(dir -> VersionedResource.find(dir, identifier, time));
    }

    @Override
    protected Boolean write(final IRI identifier, final Stream<? extends Quad> remove,
            final Stream<? extends Quad> add, final Instant time) {
        final File dir = resourceDirectory(partitions, identifier);
        if (isNull(dir)) {
            return false;
        }
        dir.mkdirs();
        return RDFPatch.write(new File(dir, RESOURCE_JOURNAL), remove, add, time) &&
            (async || CachedResource.write(dir, identifier));
    }

    @Override
    public Stream<IRI> compact(final IRI identifier) {
        throw new UnsupportedOperationException("compact is not implemented");
    }

    @Override
    public Stream<IRI> purge(final IRI identifier) {
        final List<IRI> binaries = new ArrayList<>();
        final File directory = resourceDirectory(partitions, identifier);
        final File history = new File(directory, RESOURCE_JOURNAL);
        final InterProcessLock lock = getLock(identifier);

        try {
            if (!lock.acquire(Long.parseLong(System.getProperty("zk.lock.wait.ms", "100")), MILLISECONDS)) {
                throw new UncheckedIOException(new IOException("Could not acquire resource lock!"));
            }
        } catch (final Exception ex) {
            LOGGER.error("Error acquiring resource lock: {}", ex.getMessage());
        }

        try (final Stream<String> lineStream = lines(history.toPath())) {
            lineStream.flatMap(line -> {
                final String[] parts = line.split(" ", 6);
                if (parts.length == 6 && parts[0].equals("A") &&
                        parts[1].equals(identifier.toString()) &&
                        parts[2].equals(DC.hasPart.toString()) &&
                        parts[4].equals(Trellis.PreferServerManaged.toString())) {
                    return of(parts[3]);
                }
                return empty();
            }).map(iri -> iri.substring(1, iri.length() - 1)).map(rdf::createIRI).forEach(binaries::add);
        } catch (final IOException ex) {
            LOGGER.error("Error processing journal file: {}", ex.getMessage());
            throw new UncheckedIOException(ex);
        }
        try {
            deleteIfExists(new File(directory, RESOURCE_JOURNAL).toPath());
            deleteIfExists(new File(directory, RESOURCE_CACHE).toPath());
            deleteIfExists(new File(directory, RESOURCE_QUADS).toPath());
            deleteIfExists(new File(directory, MEMENTO_CACHE).toPath());
        } catch (final IOException ex) {
            LOGGER.error("Error deleting files: {}", ex.getMessage());
            throw new UncheckedIOException(ex);
        }

        try {
            lock.release();
        } catch (final Exception ex) {
            LOGGER.error("Error releasing resource lock: {}", ex.getMessage());
            throw new UncheckedIOException(new IOException("Error releasing resource lock: " + ex.getMessage()));
        }

        return binaries.stream();
    }

    @Override
    public Stream<Triple> list(final String partition) {
        if (partitions.containsKey(partition)) {
            try {
                return walk(new File(partitions.get(partition)).toPath(), FileUtils.MAX + 2)
                    .filter(p -> p.endsWith(RESOURCE_CACHE)).map(Path::getParent).map(Path::toFile)
                    .map(CachedResource::read)
                    // TODO - JDK9 optional to stream
                    .flatMap(res -> res.map(Stream::of).orElseGet(Stream::empty)).map(data ->
                        rdf.createTriple(rdf.createIRI(data.getId()), RDF.type, rdf.createIRI(data.getLdpType())));
            } catch (final IOException ex) {
                LOGGER.error("Error reading partition root: {}", ex.getMessage());
            }
        }
        return empty();
    }

    private void init() throws IOException {
        for (final Map.Entry<String, String> storage : partitions.entrySet()) {
            final File data = storage.getValue().startsWith("file:") ?
                 new File(create(storage.getValue())) : new File(storage.getValue());
            LOGGER.info("Using resource data directory for '{}': {}", storage.getKey(), data.getAbsolutePath());
            if (!data.exists()) {
                data.mkdirs();
            }
            if (!data.canWrite()) {
                throw new IOException("Cannot write to " + data.getAbsolutePath());
            }
            final IRI identifier = rdf.createIRI(TRELLIS_PREFIX + storage.getKey());
            final IRI authIdentifier = rdf.createIRI(TRELLIS_PREFIX + storage.getKey() + "#auth");
            final File root = resourceDirectory(partitions, identifier);
            final File rootData = new File(root, RESOURCE_JOURNAL);

            if (!root.exists() || !rootData.exists()) {
                LOGGER.info("Initializing root container for '{}'", identifier.getIRIString());
                root.mkdirs();
                final Instant time = now();
                final IRI skolem = (IRI) skolemize(rdf.createBlankNode());
                final Stream<Quad> quads = of(
                        rdf.createQuad(Trellis.PreferServerManaged, identifier, RDF.type, LDP.Container),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, RDF.type, ACL.Authorization),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, ACL.mode, ACL.Read),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, ACL.mode, ACL.Write),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, ACL.mode, ACL.Control),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, ACL.accessTo, identifier),
                        rdf.createQuad(Trellis.PreferAccessControl, authIdentifier, ACL.agentClass, FOAF.Agent),
                        rdf.createQuad(Trellis.PreferAudit, identifier, PROV.wasGeneratedBy, skolem),
                        rdf.createQuad(Trellis.PreferAudit, skolem, RDF.type, PROV.Activity),
                        rdf.createQuad(Trellis.PreferAudit, skolem, RDF.type, AS.Create),
                        rdf.createQuad(Trellis.PreferAudit, skolem, PROV.wasAssociatedWith,
                            Trellis.RepositoryAdministrator),
                        rdf.createQuad(Trellis.PreferAudit, skolem, PROV.generatedAtTime,
                            rdf.createLiteral(time.toString(), XSD.dateTime)));
                RDFPatch.write(rootData, empty(), quads, now());
                CachedResource.write(root, identifier);
            }
        }
    }
}
