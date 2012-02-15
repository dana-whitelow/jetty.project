/*
 * Copyright (c) 2012 the original author or authors.
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

package org.eclipse.jetty.spdy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SynReplyTest extends AbstractTest
{
    @Test
    public void testSynReply() throws Exception
    {
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        final CountDownLatch synLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                sessionRef.set(session);
            }

            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isHalfClosed());
                stream.reply(new ReplyInfo(new Headers(), true));
                synLatch.countDown();
                return null;
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch streamCreatedLatch = new CountDownLatch(1);
        final CountDownLatch streamRemovedLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                streamCreatedLatch.countDown();
            }

            @Override
            public void onStreamClosed(Stream stream)
            {
                streamRemovedLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(new Headers(), true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(stream.isClosed());
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = sessionRef.get();
        Assert.assertNotNull(serverSession);

        Assert.assertTrue(streamCreatedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(stream.isClosed());

        Assert.assertTrue(streamRemovedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testSynDataReply() throws Exception
    {
        final byte[] dataBytes = "foo".getBytes(Charset.forName("UTF-8"));

        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                sessionRef.set(session);
            }

            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertFalse(stream.isHalfClosed());
                synLatch.countDown();
                return new Stream.FrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ByteBuffer buffer = ByteBuffer.allocate(2);
                        while (!dataInfo.isConsumed())
                        {
                            dataInfo.getBytes(buffer);
                            buffer.flip();
                            bytes.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                            buffer.clear();
                        }
                        Assert.assertTrue(Arrays.equals(dataBytes, bytes.toByteArray()));
                        Assert.assertTrue(stream.isHalfClosed());
                        Assert.assertFalse(stream.isClosed());

                        stream.reply(new ReplyInfo(new Headers(), true));
                        Assert.assertTrue(stream.isClosed());
                        dataLatch.countDown();
                    }
                };
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch streamRemovedLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamClosed(Stream stream)
            {
                streamRemovedLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(new Headers(), false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(stream.isClosed());
                replyLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(dataBytes, true));

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = sessionRef.get();
        Assert.assertNotNull(serverSession);

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(streamRemovedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testSynReplyDataFlushData() throws Exception
    {
        final String data1 = "foo";
        final String data2 = "bar";
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isHalfClosed());

                stream.reply(new ReplyInfo(false));
                stream.data(new StringDataInfo(data1, false));
                stream.getSession().flush();
                stream.data(new StringDataInfo(data2, true));

                return null;
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch1 = new CountDownLatch(1);
        final CountDownLatch dataLatch2 = new CountDownLatch(1);
        session.syn(new SynInfo(true), new Stream.FrameListener.Adapter()
        {
            private AtomicInteger dataCount = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                final ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();
                int dataCount = this.dataCount.incrementAndGet();
                if (dataCount == 1)
                {
                    String chunk1 = Charset.forName("UTF-8").decode(buffer).toString();
                    Assert.assertEquals(data1, chunk1);
                    dataLatch1.countDown();
                }
                else if (dataCount == 2)
                {
                    String chunk2 = Charset.forName("UTF-8").decode(buffer).toString();
                    Assert.assertEquals(data2, chunk2);
                    dataLatch2.countDown();
                }
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch2.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSynDataReplyData() throws Exception
    {
        final String serverData = "server";
        final String clientData = "client";

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch clientDataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                Stream stream = session.syn(new SynInfo(false), new Stream.FrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        replyLatch.countDown();
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                        dataInfo.getBytes(buffer);
                        buffer.flip();
                        String data = Charset.forName("UTF-8").decode(buffer).toString();
                        Assert.assertEquals(clientData, data);
                        clientDataLatch.countDown();
                    }
                });
                stream.data(new StringDataInfo(serverData, true));
            }
        };

        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertEquals(0, stream.getId() % 2);

                stream.reply(new ReplyInfo(false));
                stream.data(new StringDataInfo(clientData, true));
                synLatch.countDown();

                return new Stream.FrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                        dataInfo.getBytes(buffer);
                        buffer.flip();
                        String data = Charset.forName("UTF-8").decode(buffer).toString();
                        Assert.assertEquals(serverData, data);
                        serverDataLatch.countDown();
                    }
                };
            }
        };

        startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSynDataRst() throws Exception
    {
        final AtomicReference<RstInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                // Do not send the reply, we expect a RST_STREAM
                stream.data(new StringDataInfo("foo", true));
                return null;
            }

            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                ref.set(rstInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), null);

        Stream stream = session.syn(new SynInfo(true), null);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        RstInfo rstInfo = ref.get();
        Assert.assertNotNull(rstInfo);
        Assert.assertEquals(stream.getId(), rstInfo.getStreamId());
        Assert.assertSame(StreamStatus.PROTOCOL_ERROR, rstInfo.getStreamStatus());
    }
}
