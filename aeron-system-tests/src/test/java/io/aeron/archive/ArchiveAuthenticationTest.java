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
package io.aeron.archive;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.security.Authenticator;
import io.aeron.security.AuthenticatorSupplier;
import io.aeron.security.CredentialsSupplier;
import io.aeron.security.SessionProxy;
import org.agrona.CloseHelper;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Test;

import java.io.File;

import static io.aeron.archive.Common.*;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static io.aeron.security.NullCredentialsSupplier.NULL_CREDENTIAL;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;

public class ArchiveAuthenticationTest
{
    private static final int RECORDED_STREAM_ID = 33;
    private static final String RECORDED_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .termLength(Common.TERM_LENGTH)
        .build();

    private static final String CREDENTIALS_STRING = "username=\"admin\"|password=\"secret\"";
    private static final String CHALLENGE_STRING = "I challenge you!";
    private static final String PRINCIPAL_STRING = "I am THE Principal!";

    private final byte[] encodedCredentials = CREDENTIALS_STRING.getBytes();
    private final byte[] encodedChallenge = CHALLENGE_STRING.getBytes();

    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private AeronArchive aeronArchive;

    private final String aeronDirectoryName = CommonContext.generateRandomDirName();

    @After
    public void after()
    {
        CloseHelper.close(aeronArchive);
        CloseHelper.close(aeron);
        CloseHelper.close(archivingMediaDriver);

        archivingMediaDriver.archive().context().deleteArchiveDirectory();
    }

    @Test(timeout = 10_000)
    public void shouldBeAbleToRecordWithDefaultCredentialsAndAuthenticator()
    {
        launchArchivingMediaDriver(null);
        connectClient(null);

        createRecording();
    }

    @Test(timeout = 10_000)
    public void shouldBeAbleToRecordWithAuthenticateOnConnectRequestWithCredentials()
    {
        final MutableLong authenticatorSessionId = new MutableLong(-1L);

        final CredentialsSupplier credentialsSupplier = spy(new CredentialsSupplier()
        {
            public byte[] encodedCredentials()
            {
                return encodedCredentials;
            }

            public byte[] onChallenge(final byte[] encodedChallenge)
            {
                fail();
                return null;
            }
        });

        final Authenticator authenticator = spy(new Authenticator()
        {
            public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                authenticatorSessionId.value = sessionId;
                assertThat(new String(encodedCredentials), is(CREDENTIALS_STRING));
            }

            public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                fail();
            }

            public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                sessionProxy.authenticate(PRINCIPAL_STRING.getBytes());
            }

            public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                fail();
            }
        });

        launchArchivingMediaDriver(() -> authenticator);
        connectClient(credentialsSupplier);

        assertThat(authenticatorSessionId.value, is(aeronArchive.controlSessionId()));

        createRecording();
    }

    @Test(timeout = 10_000)
    public void shouldBeAbleToRecordWithAuthenticateOnChallengeResponse()
    {
        final MutableLong authenticatorSessionId = new MutableLong(-1L);

        final CredentialsSupplier credentialsSupplier = spy(new CredentialsSupplier()
        {
            public byte[] encodedCredentials()
            {
                return NULL_CREDENTIAL;
            }

            public byte[] onChallenge(final byte[] encodedChallenge)
            {
                assertThat(new String(encodedChallenge), is(CHALLENGE_STRING));
                return encodedCredentials;
            }
        });

        final Authenticator authenticator = spy(new Authenticator()
        {
            boolean challengeSuccessful = false;

            public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                authenticatorSessionId.value = sessionId;
                assertThat(encodedCredentials.length, is(0));
            }

            public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionId));
                assertThat(new String(encodedCredentials), is(CREDENTIALS_STRING));
                challengeSuccessful = true;
            }

            public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                sessionProxy.challenge(encodedChallenge);
            }

            public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                if (challengeSuccessful)
                {
                    assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                    sessionProxy.authenticate(PRINCIPAL_STRING.getBytes());
                }
            }
        });

        launchArchivingMediaDriver(() -> authenticator);
        connectClient(credentialsSupplier);

        assertThat(authenticatorSessionId.value, is(aeronArchive.controlSessionId()));

        createRecording();
    }

    @Test(timeout = 10_000)
    public void shouldNotBeAbleToConnectWithRejectOnConnectRequest()
    {
        final MutableLong authenticatorSessionId = new MutableLong(-1L);

        final CredentialsSupplier credentialsSupplier = spy(new CredentialsSupplier()
        {
            public byte[] encodedCredentials()
            {
                return NULL_CREDENTIAL;
            }

            public byte[] onChallenge(final byte[] encodedChallenge)
            {
                assertThat(new String(encodedChallenge), is(CHALLENGE_STRING));
                return encodedCredentials;
            }
        });

        final Authenticator authenticator = spy(new Authenticator()
        {
            public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                authenticatorSessionId.value = sessionId;
                assertThat(encodedCredentials.length, is(0));
            }

            public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                fail();
            }

            public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                sessionProxy.reject();
            }

            public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                fail();
            }
        });

        launchArchivingMediaDriver(() -> authenticator);

        try
        {
            connectClient(credentialsSupplier);
        }
        catch (final ArchiveException ex)
        {
            assertThat(ex.errorCode(), is(ArchiveException.AUTHENTICATION_REJECTED));
            return;
        }

        fail("should have seen exception");
    }

    @Test(timeout = 10_000)
    public void shouldNotBeAbleToConnectWithRejectOnChallengeResponse()
    {
        final MutableLong authenticatorSessionId = new MutableLong(-1L);

        final CredentialsSupplier credentialsSupplier = spy(new CredentialsSupplier()
        {
            public byte[] encodedCredentials()
            {
                return NULL_CREDENTIAL;
            }

            public byte[] onChallenge(final byte[] encodedChallenge)
            {
                assertThat(new String(encodedChallenge), is(CHALLENGE_STRING));
                return encodedCredentials;
            }
        });

        final Authenticator authenticator = spy(new Authenticator()
        {
            boolean challengeRespondedTo = false;

            public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                authenticatorSessionId.value = sessionId;
                assertThat(encodedCredentials.length, is(0));
            }

            public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionId));
                assertThat(new String(encodedCredentials), is(CREDENTIALS_STRING));
                challengeRespondedTo = true;
            }

            public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                sessionProxy.challenge(encodedChallenge);
            }

            public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
            {
                if (challengeRespondedTo)
                {
                    assertThat(authenticatorSessionId.value, is(sessionProxy.sessionId()));
                    sessionProxy.reject();
                }
            }
        });

        launchArchivingMediaDriver(() -> authenticator);

        try
        {
            connectClient(credentialsSupplier);
        }
        catch (final ArchiveException ex)
        {
            assertThat(ex.errorCode(), is(ArchiveException.AUTHENTICATION_REJECTED));
            return;
        }

        fail("should have seen exception");
    }

    private void connectClient(final CredentialsSupplier credentialsSupplier)
    {
        aeron = Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(aeronDirectoryName));

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .credentialsSupplier(credentialsSupplier)
                .aeron(aeron));
    }

    private void launchArchivingMediaDriver(final AuthenticatorSupplier authenticatorSupplier)
    {
        archivingMediaDriver = ArchivingMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .termBufferSparseFile(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(Throwable::printStackTrace)
                .spiesSimulateConnection(false)
                .dirDeleteOnShutdown(true)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(Common.MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirectoryName)
                .deleteArchiveOnStart(true)
                .archiveDir(new File(SystemUtil.tmpDirName(), "archive"))
                .fileSyncLevel(0)
                .authenticatorSupplier(authenticatorSupplier)
                .threadingMode(ArchiveThreadingMode.SHARED));
    }

    private void createRecording()
    {
        final String messagePrefix = "Message-Prefix-";
        final int messageCount = 10;

        final long subscriptionId = aeronArchive.startRecording(RECORDED_CHANNEL, RECORDED_STREAM_ID, LOCAL);

        try (Subscription subscription = aeron.addSubscription(RECORDED_CHANNEL, RECORDED_STREAM_ID);
            Publication publication = aeron.addPublication(RECORDED_CHANNEL, RECORDED_STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Common.awaitRecordingCounterId(counters, publication.sessionId());

            offer(publication, messageCount, messagePrefix);
            consume(subscription, messageCount, messagePrefix);

            final long currentPosition = publication.position();
            awaitPosition(counters, counterId, currentPosition);
        }

        aeronArchive.stopRecording(subscriptionId);
    }
}
