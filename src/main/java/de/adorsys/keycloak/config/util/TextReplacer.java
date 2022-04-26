/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.util;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * @author hazem
 */
@Component
public class TextReplacer {

    public String replaceAll(final String text, final Map<String, String> definitions) {
        // Create a buffer sufficiently large that re-allocations are minimized.
        final StringBuilder sb = new StringBuilder(text.length() << 1);

        final Trie.TrieBuilder builder = Trie.builder();
        builder.onlyWholeWords();
        builder.ignoreOverlaps();

        for (final String key : definitions.keySet()) {
            builder.addKeyword(key);
        }

        final Trie trie = builder.build();
        final Collection<Emit> emits = trie.parseText(text);

        int prevIndex = 0;

        for (final Emit emit : emits) {
            final int matchIndex = emit.getStart();

            sb.append(text.substring(prevIndex, matchIndex));
            sb.append(definitions.get(emit.getKeyword()));
            prevIndex = emit.getEnd() + 1;
        }

        // Add the remainder of the string (contains no more matches).
        sb.append(text.substring(prevIndex));

        return sb.toString();
    }
}
