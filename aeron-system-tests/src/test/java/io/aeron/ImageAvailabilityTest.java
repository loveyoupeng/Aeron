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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.test.TestMediaDriver;
import org.agrona.CloseHelper;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(Theories.class)
public class ImageAvailabilityTest
{
    @DataPoint
    public static final String IPC_CHANNEL = "aeron:ipc?term-length=64k";

    @DataPoint
    public static final String UNICAST_CHANNEL = "aeron:udp?endpoint=localhost:54325|term-length=64k";

    @DataPoint
    public static final String MULTICAST_CHANNEL = "aeron:udp?endpoint=224.20.30.39:54326|interface=localhost";

    private static final int STREAM_ID = 1;

    private final TestMediaDriver driver = TestMediaDriver.launch(new MediaDriver.Context()
        .errorHandler(Throwable::printStackTrace)
        .dirDeleteOnShutdown(true)
        .dirDeleteOnStart(true)
        .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(20))
        .threadingMode(ThreadingMode.SHARED));

    private final Aeron aeron = Aeron.connect(new Aeron.Context()
        .useConductorAgentInvoker(true)
        .errorHandler(Throwable::printStackTrace));

    @After
    public void after()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(driver);
    }

    @Theory
    @Test(timeout = 10_000)
    public void shouldCallImageHandlers(final String channel)
    {
        final AtomicInteger unavailableImageCount = new AtomicInteger();
        final AtomicInteger availableImageCount = new AtomicInteger();
        final UnavailableImageHandler unavailableHandler = (image) -> unavailableImageCount.incrementAndGet();
        final AvailableImageHandler availableHandler = (image) -> availableImageCount.incrementAndGet();

        final String spyChannel = channel.contains("ipc") ? channel : CommonContext.SPY_PREFIX + channel;

        try (Subscription subOne = aeron.addSubscription(channel, STREAM_ID, availableHandler, unavailableHandler);
            Subscription subTwo = aeron.addSubscription(spyChannel, STREAM_ID, availableHandler, unavailableHandler);
            Publication publication = aeron.addPublication(channel, STREAM_ID))
        {
            while (!subOne.isConnected() || !subTwo.isConnected() || !publication.isConnected())
            {
                aeron.conductorAgentInvoker().invoke();
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            final Image image = subOne.imageAtIndex(0);
            final Image spyImage = subTwo.imageAtIndex(0);

            assertFalse(image.isClosed());
            assertFalse(image.isEndOfStream());
            assertFalse(spyImage.isClosed());
            assertFalse(spyImage.isEndOfStream());

            assertEquals(2, availableImageCount.get());
            assertEquals(0, unavailableImageCount.get());

            publication.close();

            while (subOne.isConnected() || subTwo.isConnected())
            {
                aeron.conductorAgentInvoker().invoke();
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            assertTrue(image.isClosed());
            assertTrue(image.isEndOfStream());
            assertTrue(spyImage.isClosed());
            assertTrue(spyImage.isEndOfStream());

            assertEquals(2, availableImageCount.get());
            assertEquals(2, unavailableImageCount.get());
        }
    }

    @Theory
    @Test(timeout = 10_000)
    public void shouldCallImageHandlersWithPublisherOnDifferentClient(final String channel)
    {
        final AtomicInteger unavailableImageCount = new AtomicInteger();
        final AtomicInteger availableImageCount = new AtomicInteger();
        final UnavailableImageHandler unavailableHandler = (image) -> unavailableImageCount.incrementAndGet();
        final AvailableImageHandler availableHandler = (image) -> availableImageCount.incrementAndGet();

        final String spyChannel = channel.contains("ipc") ? channel : CommonContext.SPY_PREFIX + channel;
        final Aeron.Context ctx = new Aeron.Context()
            .useConductorAgentInvoker(true)
            .errorHandler(Throwable::printStackTrace);

        try (Aeron aeronTwo = Aeron.connect(ctx);
            Subscription subOne = aeron.addSubscription(channel, STREAM_ID, availableHandler, unavailableHandler);
            Subscription subTwo = aeron.addSubscription(spyChannel, STREAM_ID, availableHandler, unavailableHandler);
            Publication publication = aeronTwo.addPublication(channel, STREAM_ID))
        {
            while (!subOne.isConnected() || !subTwo.isConnected() || !publication.isConnected())
            {
                aeron.conductorAgentInvoker().invoke();
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            final Image image = subOne.imageAtIndex(0);
            final Image spyImage = subTwo.imageAtIndex(0);

            assertFalse(image.isClosed());
            assertFalse(image.isEndOfStream());
            assertFalse(spyImage.isClosed());
            assertFalse(spyImage.isEndOfStream());

            assertEquals(2, availableImageCount.get());
            assertEquals(0, unavailableImageCount.get());

            aeronTwo.close();

            while (subOne.isConnected() || subTwo.isConnected())
            {
                aeron.conductorAgentInvoker().invoke();
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            assertTrue(image.isClosed());
            assertTrue(image.isEndOfStream());
            assertTrue(spyImage.isClosed());
            assertTrue(spyImage.isEndOfStream());

            assertEquals(2, availableImageCount.get());
            assertEquals(2, unavailableImageCount.get());
        }
    }
}
