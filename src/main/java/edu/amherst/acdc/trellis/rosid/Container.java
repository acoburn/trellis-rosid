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

import org.apache.commons.rdf.api.IRI;
import edu.amherst.acdc.trellis.vocabulary.LDP;

/**
 * @author acoburn
 */
public class Container extends RdfSource {

    /**
     * Create an LDP Container object
     * @param resolver the resolved resource
     */
    public Container(final ResourceReader resolver) {
        super(resolver);
    }

    @Override
    public IRI getInteractionModel() {
        return LDP.Container;
    }
}
