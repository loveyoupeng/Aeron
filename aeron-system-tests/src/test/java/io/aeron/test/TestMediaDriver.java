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
package io.aeron.test;

import io.aeron.driver.MediaDriver;
import org.junit.Assume;

import static org.agrona.Strings.isEmpty;

public interface TestMediaDriver extends AutoCloseable
{
    String AERON_TEST_SYSTEM_AERONMD_PATH = "aeron.test.system.aeronmd.path";

    static boolean shouldRunCMediaDriver()
    {
        return !isEmpty(System.getProperty(AERON_TEST_SYSTEM_AERONMD_PATH));
    }

    static void notSupportedOnCMediaDriverYet(String reason)
    {
        Assume.assumeFalse("Functionality not support by C Media Driver: " + reason, shouldRunCMediaDriver());
    }

    static TestMediaDriver launch(MediaDriver.Context context)
    {
        return shouldRunCMediaDriver() ?
            CTestMediaDriver.launch(context, null) : JavaTestMediaDriver.launch(context);
    }

    static TestMediaDriver launch(MediaDriver.Context context, DriverOutputConsumer driverOutputConsumer)
    {
        return shouldRunCMediaDriver() ?
            CTestMediaDriver.launch(context, driverOutputConsumer) : JavaTestMediaDriver.launch(context);
    }

    MediaDriver.Context context();

    String aeronDirectoryName();

    void close();
}
