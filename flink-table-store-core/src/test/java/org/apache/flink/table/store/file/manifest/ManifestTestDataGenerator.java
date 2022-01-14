/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.manifest;

import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.store.file.KeyValue;
import org.apache.flink.table.store.file.TestKeyValueGenerator;
import org.apache.flink.table.store.file.ValueKind;
import org.apache.flink.table.store.file.mergetree.sst.SstTestDataGenerator;
import org.apache.flink.table.store.file.stats.FieldStatsCollector;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Random {@link ManifestEntry} generator. */
public class ManifestTestDataGenerator {

    private static final int LEVEL_CAPACITY = 3;

    private final int numBuckets;
    private final List<Map<BinaryRowData, List<List<SstTestDataGenerator.Data>>>> levels;
    private final SstTestDataGenerator gen;

    private final LinkedList<ManifestEntry> bufferedResults;

    private ManifestTestDataGenerator(int numBuckets, int memTableCapacity) {
        this.numBuckets = numBuckets;
        this.levels = new ArrayList<>();
        for (int i = 0; i < numBuckets; i++) {
            levels.add(new HashMap<>());
        }
        this.gen =
                SstTestDataGenerator.builder()
                        .numBuckets(numBuckets)
                        .memTableCapacity(memTableCapacity)
                        .build();

        this.bufferedResults = new LinkedList<>();
    }

    public ManifestEntry next() {
        if (bufferedResults.size() > 0) {
            return bufferedResults.poll();
        }

        SstTestDataGenerator.Data file = gen.next();
        List<List<SstTestDataGenerator.Data>> bucketLevels =
                levels.get(file.bucket).computeIfAbsent(file.partition, k -> new ArrayList<>());
        ensureCapacity(bucketLevels, file.meta.level());
        List<SstTestDataGenerator.Data> level = bucketLevels.get(file.meta.level());
        level.add(file);
        bufferedResults.push(
                new ManifestEntry(
                        ValueKind.ADD, file.partition, file.bucket, numBuckets, file.meta));
        mergeLevelsIfNeeded(file.partition, file.bucket);

        return bufferedResults.poll();
    }

    public ManifestFileMeta createManifestFileMeta(List<ManifestEntry> entries) {
        Preconditions.checkArgument(
                !entries.isEmpty(), "Manifest entries are empty. Invalid test data.");

        FieldStatsCollector collector =
                new FieldStatsCollector(TestKeyValueGenerator.PARTITION_TYPE);

        long numAddedFiles = 0;
        long numDeletedFiles = 0;
        for (ManifestEntry entry : entries) {
            collector.collect(entry.partition());
            if (entry.kind() == ValueKind.ADD) {
                numAddedFiles++;
            } else {
                numDeletedFiles++;
            }
        }

        return new ManifestFileMeta(
                "manifest-" + UUID.randomUUID(),
                entries.size() * 100L,
                numAddedFiles,
                numDeletedFiles,
                collector.extract());
    }

    private void mergeLevelsIfNeeded(BinaryRowData partition, int bucket) {
        // this method uses a very simple merging strategy just for producing valid data
        List<List<SstTestDataGenerator.Data>> bucketLevels = levels.get(bucket).get(partition);
        int lastModifiedLevel = 0;
        while (bucketLevels.get(lastModifiedLevel).size() > LEVEL_CAPACITY) {

            // remove all sst files in the current and next level
            ensureCapacity(bucketLevels, lastModifiedLevel + 1);
            List<SstTestDataGenerator.Data> currentLevel = bucketLevels.get(lastModifiedLevel);
            List<SstTestDataGenerator.Data> nextLevel = bucketLevels.get(lastModifiedLevel + 1);
            List<KeyValue> kvs = new ArrayList<>();

            for (SstTestDataGenerator.Data file : currentLevel) {
                bufferedResults.push(
                        new ManifestEntry(
                                ValueKind.DELETE, partition, bucket, numBuckets, file.meta));
                kvs.addAll(file.content);
            }
            currentLevel.clear();

            for (SstTestDataGenerator.Data file : nextLevel) {
                bufferedResults.push(
                        new ManifestEntry(
                                ValueKind.DELETE, partition, bucket, numBuckets, file.meta));
                kvs.addAll(file.content);
            }
            nextLevel.clear();

            // add back merged sst files
            List<SstTestDataGenerator.Data> merged =
                    gen.createSstFiles(kvs, lastModifiedLevel + 1, partition, bucket);
            nextLevel.addAll(merged);
            for (SstTestDataGenerator.Data file : nextLevel) {
                bufferedResults.push(
                        new ManifestEntry(ValueKind.ADD, partition, bucket, numBuckets, file.meta));
            }

            lastModifiedLevel += 1;
        }
    }

    private void ensureCapacity(List<List<SstTestDataGenerator.Data>> list, int capacity) {
        while (list.size() <= capacity) {
            list.add(new ArrayList<>());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ManifestTestDataGenerator}. */
    public static class Builder {
        private int numBuckets = 3;
        private int memTableCapacity = 3;

        public Builder numBuckets(int value) {
            this.numBuckets = value;
            return this;
        }

        public Builder memTableCapacity(int value) {
            this.memTableCapacity = value;
            return this;
        }

        public ManifestTestDataGenerator build() {
            return new ManifestTestDataGenerator(numBuckets, memTableCapacity);
        }
    }
}