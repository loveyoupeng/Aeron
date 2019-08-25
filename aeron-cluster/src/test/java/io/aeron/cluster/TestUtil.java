/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.cluster.client.ClusterException;
import io.aeron.exceptions.AeronException;
import org.agrona.ErrorHandler;
import org.agrona.SystemUtil;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.status.CountersReader;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.fail;

class TestUtil
{
    public static final Runnable TERMINATION_HOOK =
        () ->
        {
            throw new AgentTerminationException();
        };

    public static void checkInterruptedStatus()
    {
        if (Thread.currentThread().isInterrupted())
        {
            fail("unexpected interrupt - test likely to have timed out");
        }
    }

    public static Runnable dynamicTerminationHook(
        final AtomicBoolean terminationExpected, final AtomicBoolean wasTerminated)
    {
        return () ->
        {
            if (null == terminationExpected || !terminationExpected.get())
            {
                throw new AgentTerminationException();
            }

            if (null != wasTerminated)
            {
                wasTerminated.set(true);
            }
        };
    }

    public static ErrorHandler errorHandler(final int nodeId)
    {
        return
            (ex) ->
            {
                if (ex instanceof ClusterException)
                {
                    if (((ClusterException)ex).category() == AeronException.Category.WARN)
                    {
                        return;
                    }
                }

                System.err.println("\n*** Error in node " + nodeId + " followed by system thread dump ***\n\n");
                ex.printStackTrace();

                System.err.println();
                System.err.println(SystemUtil.threadDump());
            };
    }

    public static void printCounters(final CountersReader countersReader, final PrintStream out)
    {
        countersReader.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                final long value = countersReader.getCounterValue(counterId);
                out.format("%3d: %,20d - %s%n", counterId, value, label);
            });
    }
}
