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
package edu.amherst.acdc.trellis.rosid;

import static java.time.Instant.now;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.time.Instant.parse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import edu.amherst.acdc.trellis.vocabulary.DC;
import edu.amherst.acdc.trellis.vocabulary.Trellis;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Quad;
import org.apache.commons.rdf.api.RDF;
import org.apache.commons.rdf.jena.JenaRDF;
import org.junit.Before;
import org.junit.Test;

/**
 * @author acoburn
 */
public class RDFPatchTest {

    private static final RDF rdf = new JenaRDF();
    private static final IRI identifier = rdf.createIRI("info:trellis/resource");

    @Before
    public void setUp() throws IOException {
        final File dir = new File("build/data");
        dir.mkdirs();
    }

    @Test
    public void testStream1() throws Exception {
        final File file = new File(getClass().getResource("/journal1.txt").toURI());
        final Instant time = parse("2017-02-11T02:51:35Z");
        final Graph graph = rdf.createGraph();
        RDFPatch.asStream(rdf, file, identifier, time).map(Quad::asTriple).forEach(graph::add);
        assertEquals(2L, graph.size());
        assertTrue(graph.contains(identifier, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), null));
    }

    @Test
    public void testStream2() throws Exception {
        final File file = new File(getClass().getResource("/journal1.txt").toURI());
        final Instant time = parse("2017-02-09T02:51:35Z");
        final Graph graph = rdf.createGraph();
        RDFPatch.asStream(rdf, file, identifier, time).map(Quad::asTriple).forEach(graph::add);
        assertEquals(3L, graph.size());
        assertTrue(graph.contains(identifier, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), null));
        assertTrue(graph.contains(identifier, DC.isPartOf, null));
    }

    @Test
    public void testStream3() throws Exception {
        final File file = new File(getClass().getResource("/journal1.txt").toURI());
        final Instant time = parse("2017-01-30T02:51:35Z");
        final Graph graph = rdf.createGraph();
        RDFPatch.asStream(rdf, file, identifier, time).map(Quad::asTriple).forEach(graph::add);
        assertEquals(7L, graph.size());
        assertFalse(graph.contains(identifier, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), null));
        assertTrue(graph.contains(identifier, DC.extent, null));
        assertTrue(graph.contains(identifier, DC.spatial, null));
        assertTrue(graph.contains(identifier, DC.title, null));
        assertTrue(graph.contains(identifier, DC.description, null));
        assertTrue(graph.contains(identifier, DC.subject, null));
        assertEquals(2L, graph.stream(identifier, DC.subject, null).count());
    }

    @Test
    public void testStream4() throws Exception {
        final File file = new File(getClass().getResource("/journal1.txt").toURI());
        final Instant time = parse("2017-01-15T09:14:00Z");
        final Graph graph = rdf.createGraph();
        RDFPatch.asStream(rdf, file, identifier, time).map(Quad::asTriple).forEach(graph::add);
        assertEquals(5L, graph.size());
        assertFalse(graph.contains(identifier, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), null));
        assertFalse(graph.contains(identifier, DC.extent, null));
        assertFalse(graph.contains(identifier, DC.spatial, null));
        assertTrue(graph.contains(identifier, DC.title, null));
        assertTrue(graph.contains(identifier, DC.description, null));
        assertTrue(graph.contains(identifier, DC.subject, null));
        assertEquals(2L, graph.stream(identifier, DC.subject, null).count());
    }

    @Test
    public void testVersionWriter() throws IOException {
        final File file = new File("build/data/resource.rdfp");
        final Instant time = now();
        final List<Quad> delete = emptyList();
        final List<Quad> add = new ArrayList<>();
        add.add(rdf.createQuad(Trellis.PreferUserManaged, identifier, DC.title, rdf.createLiteral("Title")));
        add.add(rdf.createQuad(Trellis.PreferUserManaged, identifier, DC.description,
                    rdf.createLiteral("A longer description")));
        RDFPatch.write(file, delete.stream(), add.stream(), time);
        final List<Quad> data = RDFPatch.asStream(rdf, file, identifier, time).collect(toList());
        assertEquals(data.size(), add.size() + 1);
        add.forEach(q -> assertTrue(data.contains(q)));
    }
}
