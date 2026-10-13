// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.rendering;

import com.yahoo.data.access.slime.SlimeAdapter;
import com.yahoo.prelude.fastsearch.DocsumDefinition;
import com.yahoo.prelude.fastsearch.FastHit;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.schema.DocumentSummary;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.slime.BinaryFormat;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Measures JSON rendering of 100 hits, each with 2 double fields and 32 double summary features.
 * Each render decodes the summaries from binary Slime, as the container does for each request.
 * Run main() and compare the output before and after a change.
 *
 * @author buinauskas
 */
public class JsonRendererDoubleMicroBenchmark {

    private static final int HITS = 100;
    private static final int OPS = 2_000;

    public static void main(String[] args) throws Exception {
        var summary = new DocumentSummary.Builder("default");
        summary.add(new DocumentSummary.Field("price", "double"));
        summary.add(new DocumentSummary.Field("score", "double"));
        summary.add(new DocumentSummary.Field("summaryfeatures", "featuredata"));
        DocsumDefinition docsum = new DocsumDefinition(summary.build());

        Random random = new Random(42);
        byte[][] summaries = new byte[HITS][];
        for (int i = 0; i < HITS; i++) {
            Slime slime = new Slime();
            Cursor fields = slime.setObject();
            fields.setDouble("price", random.nextDouble() * 1000);
            fields.setDouble("score", random.nextDouble());
            Cursor features = fields.setObject("summaryfeatures");
            for (int f = 0; f < 32; f++)
                features.setDouble("feature_" + f, random.nextGaussian());
            summaries[i] = BinaryFormat.encode(slime);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        JsonRenderer blueprint = new JsonRenderer(executor);
        Execution execution = new Execution(Execution.Context.createContextStub());
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);

        int rounds = 20, warmup = 10;
        double[] microsPerOp = new double[rounds - warmup];
        long bytesPerOp = 0;
        for (int round = 0; round < rounds; round++) {
            long bytes = allocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                out.reset();
                JsonRenderer renderer = (JsonRenderer) blueprint.clone();
                renderer.init();
                renderer.renderResponse(out, createResult(docsum, summaries), execution, null).get();
                renderer.deconstruct();
            }
            long nanos = System.nanoTime() - start;
            if (round < warmup) continue;
            microsPerOp[round - warmup] = nanos / 1000.0 / OPS;
            bytesPerOp = (allocatedBytes() - bytes) / OPS;
        }
        Arrays.sort(microsPerOp);
        System.out.printf("Render %d hits with double fields: median %.1f us/op (min %.1f, max %.1f), %d B/op, %d B output%n",
                          HITS, microsPerOp[microsPerOp.length / 2], microsPerOp[0], microsPerOp[microsPerOp.length - 1],
                          bytesPerOp, out.size());
        executor.shutdown();
    }

    /** Creates a new result for each render, because rendering releases the result. */
    private static Result createResult(DocsumDefinition docsum, byte[][] summaries) {
        Result result = new Result(new Query("/?query=a"));
        for (int i = 0; i < summaries.length; i++) {
            FastHit hit = new FastHit("id:test:test::" + i, 1.0 / (i + 3));
            hit.addSummary(docsum, new SlimeAdapter(BinaryFormat.decode(summaries[i]).get()));
            result.hits().add(hit);
        }
        return result;
    }

    /** Returns the bytes allocated by all live threads, including the rendering thread. */
    private static long allocatedBytes() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return Arrays.stream(threads.getThreadAllocatedBytes(threads.getAllThreadIds())).filter(b -> b > 0).sum();
    }

}
