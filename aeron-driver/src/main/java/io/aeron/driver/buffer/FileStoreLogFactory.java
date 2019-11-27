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
package io.aeron.driver.buffer;

import io.aeron.exceptions.AeronException;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.LangUtil;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

import static io.aeron.logbuffer.LogBufferDescriptor.computeLogLength;

/**
 * Factory for creating {@link RawLog}s in the source publications or publication images directories as appropriate.
 */
public class FileStoreLogFactory implements LogFactory
{
    private static final String PUBLICATIONS = "publications";
    private static final String IMAGES = "images";

    private long blankTemplateLength;
    private final long lowStorageWarningThreshold;
    private final int filePageSize;
    private final boolean checkStorage;
    private final ErrorHandler errorHandler;
    private final File publicationsDir;
    private final File imagesDir;
    private final FileStore fileStore;
    private final RandomAccessFile blankFile;
    private final FileChannel blankChannel;

    public FileStoreLogFactory(
        final String dataDirectoryName,
        final int filePageSize,
        final boolean checkStorage,
        final long lowStorageWarningThreshold,
        final ErrorHandler errorHandler)
    {
        this.filePageSize = filePageSize;
        this.lowStorageWarningThreshold = lowStorageWarningThreshold;
        this.checkStorage = checkStorage;
        this.errorHandler = errorHandler;

        final File dataDir = new File(dataDirectoryName);

        publicationsDir = new File(dataDir, PUBLICATIONS);
        imagesDir = new File(dataDir, IMAGES);

        IoUtil.ensureDirectoryExists(publicationsDir, PUBLICATIONS);
        IoUtil.ensureDirectoryExists(imagesDir, IMAGES);

        try
        {
            fileStore = checkStorage ? Files.getFileStore(dataDir.toPath()) : null;
            blankFile = new RandomAccessFile(new File(dataDir, "blank.template"), "rw");
            blankChannel = blankFile.getChannel();
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    public void close()
    {
        CloseHelper.close(blankChannel);
    }

    /**
     * Create new {@link RawLog} in the publications directory for the supplied triplet.
     *
     * @param channel          address on the media to send to.
     * @param sessionId        under which transmissions are made.
     * @param streamId         within the channel address to separate message flows.
     * @param correlationId    to use to distinguish this publication
     * @param termBufferLength length of each term
     * @param useSparseFiles   for the log buffer.
     * @return the newly allocated {@link RawLog}
     */
    public RawLog newPublication(
        final String channel,
        final int sessionId,
        final int streamId,
        final long correlationId,
        final int termBufferLength,
        final boolean useSparseFiles)
    {
        return newInstance(
            publicationsDir, channel, sessionId, streamId, correlationId, termBufferLength, useSparseFiles);
    }

    /**
     * Create new {@link RawLog} in the rebuilt publication images directory for the supplied triplet.
     *
     * @param channel          address on the media to listened to.
     * @param sessionId        under which transmissions are made.
     * @param streamId         within the channel address to separate message flows.
     * @param correlationId    to use to distinguish this connection
     * @param termBufferLength to use for the log buffer
     * @param useSparseFiles   for the log buffer.
     * @return the newly allocated {@link RawLog}
     */
    public RawLog newImage(
        final String channel,
        final int sessionId,
        final int streamId,
        final long correlationId,
        final int termBufferLength,
        final boolean useSparseFiles)
    {
        return newInstance(imagesDir, channel, sessionId, streamId, correlationId, termBufferLength, useSparseFiles);
    }

    private RawLog newInstance(
        final File rootDir,
        final String channel,
        final int sessionId,
        final int streamId,
        final long correlationId,
        final int termLength,
        final boolean useSparseFiles)
    {
        final long logLength = computeLogLength(termLength, filePageSize);
        checkStorage(logLength);

        if (logLength > blankTemplateLength)
        {
            try
            {
                blankFile.setLength(logLength);
            }
            catch (final IOException ex)
            {
                throw new UncheckedIOException(ex);
            }

            blankTemplateLength = logLength;
        }

        final File location = streamLocation(rootDir, channel, sessionId, streamId, correlationId);

        return new MappedRawLog(
            location, blankChannel, useSparseFiles, logLength, termLength, filePageSize, errorHandler);
    }

    private void checkStorage(final long logLength)
    {
        if (checkStorage)
        {
            final long usableSpace = getUsableSpace();

            if (usableSpace <= lowStorageWarningThreshold)
            {
                System.out.format("Warning: space is running low in %s threshold=%,d usable=%,d%n",
                    fileStore, lowStorageWarningThreshold, usableSpace);
            }

            if (usableSpace < logLength)
            {
                throw new AeronException(
                    "insufficient usable storage for new log of length=" + logLength + " in " + fileStore);
            }
        }
    }

    private long getUsableSpace()
    {
        long usableSpace = 0;

        try
        {
            usableSpace = fileStore.getUsableSpace();
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return usableSpace;
    }

    private static File streamLocation(
        final File rootDir,
        final String channel,
        final int sessionId,
        final int streamId,
        final long correlationId)
    {
        final String fileName = channel + '-' +
            Integer.toHexString(sessionId) + '-' +
            Integer.toHexString(streamId) + '-' +
            Long.toHexString(correlationId) + ".logbuffer";

        return new File(rootDir, fileName);
    }
}
