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

import static java.io.File.separator;
import static java.lang.String.join;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * @author acoburn
 */
public class FileUtilsTest {

    @Test
    public void testPartition() {

        assertEquals(join(separator, "e4", "3d", "d2", "3c11fdfba716fe4a8c2ad59720f73b3e"),
                FileUtils.partition("info:trellis/resource"));
        assertEquals(join(separator, "56", "02", "ed", "94db502039137b6017bd7089ceaf8ad1"),
                FileUtils.partition("info:trellis/other"));
    }

    @Test
    public void testPath() {
        assertEquals("foo/bar",
                FileUtils.asPath("info:trellis/foo/bar?version=0123456#hash"));
        assertEquals("foo",
                FileUtils.asPath("info:trellis/foo?version=0123456#hash"));
    }
}