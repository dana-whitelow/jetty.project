//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;

/**
 * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
 * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
 * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
 * with the channel.</p>
 */
public class ManagedSelector extends ContainerLifeCycle implements Dumpable
{
    private static final Logger LOG = Log.getLogger(ManagedSelector.class);
    private static final boolean FORCE_SELECT_NOW;

    static
    {
        String property = System.getProperty("org.eclipse.jetty.io.forceSelectNow");
        if (property != null)
        {
            FORCE_SELECT_NOW = Boolean.parseBoolean(property);
        }
        else
        {
            property = System.getProperty("os.name");
            FORCE_SELECT_NOW = property != null && property.toLowerCase(Locale.ENGLISH).contains("windows");
        }
    }

    private final AtomicBoolean _started = new AtomicBoolean(false);
    private boolean _selecting;
    private final SelectorManager _selectorManager;
    private final int _id;
    private final ExecutionStrategy _strategy;
    private Selector _selector;
    private Deque<SelectorUpdate> _updates = new ArrayDeque<>();
    private Deque<SelectorUpdate> _updateable = new ArrayDeque<>();

    public ManagedSelector(SelectorManager selectorManager, int id)
    {
        _selectorManager = selectorManager;
        _id = id;
        SelectorProducer producer = new SelectorProducer();
        Executor executor = selectorManager.getExecutor();
        _strategy = new EatWhatYouKill(producer, executor);
        addBean(_strategy, true);
        setStopTimeout(5000);
    }

    public Selector getSelector()
    {
        return _selector;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _selector = _selectorManager.newSelector();

        // The producer used by the strategies will never
        // be idle (either produces a task or blocks).

        // The normal strategy obtains the produced task, schedules
        // a new thread to produce more, runs the task and then exits.
        _selectorManager.execute(_strategy::produce);

        // Set started only if we really are started
        Start start = new Start();
        submit(start);
        start._started.await();
    }

    @Override
    protected void doStop() throws Exception
    {
        // doStop might be called for a failed managedSelector,
        // We do not want to wait twice, so we only stop once for each start
        if (_started.compareAndSet(true, false) && _selector != null)
        {
            // Close connections, but only wait a single selector cycle for it to take effect
            CloseConnections closeConnections = new CloseConnections();
            submit(closeConnections);
            closeConnections._complete.await();

            // Wait for any remaining endpoints to be closed and the selector to be stopped
            StopSelector stopSelector = new StopSelector();
            submit(stopSelector);
            stopSelector._stopped.await();
        }

        super.doStop();
    }

    protected void onSelectFailed(Throwable cause)
    {
        // override to change behavior
    }

    public int size()
    {
        Selector s = _selector;
        if (s == null)
            return 0;
        Set<SelectionKey> keys = s.keys();
        if (keys == null)
            return 0;
        return keys.size();
    }

    /**
     * Submit an {@link SelectorUpdate} to be acted on between calls to {@link Selector#select()}
     *
     * @param update The selector update to apply at next wakeup
     */
    public void submit(SelectorUpdate update)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Queued change {} on {}", update, this);

        Selector selector = null;
        synchronized (ManagedSelector.this)
        {
            _updates.offer(update);

            if (_selecting)
            {
                selector = _selector;
                // To avoid the extra select wakeup.
                _selecting = false;
            }
        }

        if (selector != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Wakeup on submit {}", this);
            selector.wakeup();
        }
    }

    private void wakeup()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Wakeup {}", this);

        Selector selector = null;
        synchronized (ManagedSelector.this)
        {
            if (_selecting)
            {
                selector = _selector;
                _selecting = false;
            }
        }

        if (selector != null)
            selector.wakeup();
    }

    private void execute(Runnable task)
    {
        try
        {
            _selectorManager.execute(task);
        }
        catch (RejectedExecutionException x)
        {
            if (task instanceof Closeable)
                IO.close((Closeable)task);
        }
    }

    private void processConnect(SelectionKey key, Connect connect)
    {
        SelectableChannel channel = key.channel();
        try
        {
            key.attach(connect.attachment);
            boolean connected = _selectorManager.doFinishConnect(channel);
            if (LOG.isDebugEnabled())
                LOG.debug("Connected {} {}", connected, channel);
            if (connected)
            {
                if (connect.timeout.cancel())
                {
                    key.interestOps(0);
                    execute(new CreateEndPoint(connect, key));
                }
                else
                {
                    throw new SocketTimeoutException("Concurrent Connect Timeout");
                }
            }
            else
            {
                throw new ConnectException();
            }
        }
        catch (Throwable x)
        {
            connect.failed(x);
        }
    }

    protected void endPointOpened(EndPoint endPoint)
    {
        _selectorManager.endPointOpened(endPoint);
    }

    protected void endPointClosed(EndPoint endPoint)
    {
        _selectorManager.endPointClosed(endPoint);
    }

    private void createEndPoint(SelectableChannel channel, SelectionKey selectionKey) throws IOException
    {
        EndPoint endPoint = _selectorManager.newEndPoint(channel, this, selectionKey);
        Connection connection = _selectorManager.newConnection(channel, endPoint, selectionKey.attachment());
        endPoint.setConnection(connection);
        selectionKey.attach(endPoint);
        endPoint.onOpen();
        endPointOpened(endPoint);
        _selectorManager.connectionOpened(connection);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", endPoint);
    }

    void destroyEndPoint(EndPoint endPoint)
    {
        // Waking up the selector is necessary to clean the
        // cancelled-key set and tell the TCP stack that the
        // socket is closed (so that senders receive RST).
        wakeup();
        execute(new DestroyEndPoint(endPoint));
    }

    private int getActionSize()
    {
        synchronized (ManagedSelector.this)
        {
            return _updates.size();
        }
    }

    static int safeReadyOps(SelectionKey selectionKey)
    {
        try
        {
            return selectionKey.readyOps();
        }
        catch (Throwable x)
        {
            LOG.ignore(x);
            return -1;
        }
    }

    static int safeInterestOps(SelectionKey selectionKey)
    {
        try
        {
            return selectionKey.interestOps();
        }
        catch (Throwable x)
        {
            LOG.ignore(x);
            return -1;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<String> keys;
        List<SelectorUpdate> updates;
        Selector selector = _selector;
        if (selector != null && selector.isOpen())
        {
            DumpKeys dump = new DumpKeys();
            String updatesAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
            synchronized (ManagedSelector.this)
            {
                updates = new ArrayList<>(_updates);
                _updates.addFirst(dump);
                _selecting = false;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("wakeup on dump {}", this);
            selector.wakeup();
            keys = dump.get(5, TimeUnit.SECONDS);
            String keysAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
            if (keys == null)
                keys = Collections.singletonList("No dump keys retrieved");

            dumpObjects(out, indent,
                new DumpableCollection("updates @ " + updatesAt, updates),
                new DumpableCollection("keys @ " + keysAt, keys));
        }
        else
        {
            dumpObjects(out, indent);
        }
    }

    @Override
    public String toString()
    {
        Selector selector = _selector;
        return String.format("%s id=%s keys=%d selected=%d updates=%d",
            super.toString(),
            _id,
            selector != null && selector.isOpen() ? selector.keys().size() : -1,
            selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1,
            getActionSize());
    }

    /**
     * A {@link Selectable} is an {@link EndPoint} that wish to be
     * notified of non-blocking events by the {@link ManagedSelector}.
     */
    public interface Selectable
    {
        /**
         * Callback method invoked when a read or write events has been
         * detected by the {@link ManagedSelector} for this endpoint.
         *
         * @return a job that may block or null
         */
        Runnable onSelected();

        /**
         * Callback method invoked when all the keys selected by the
         * {@link ManagedSelector} for this endpoint have been processed.
         */
        void updateKey();
    }

    private class SelectorProducer implements ExecutionStrategy.Producer
    {
        private Set<SelectionKey> _keys = Collections.emptySet();
        private Iterator<SelectionKey> _cursor = Collections.emptyIterator();

        @Override
        public Runnable produce()
        {
            while (true)
            {
                Runnable task = processSelected();
                if (task != null)
                    return task;

                processUpdates();

                updateKeys();

                if (!select())
                    return null;
            }
        }

        private void processUpdates()
        {
            synchronized (ManagedSelector.this)
            {
                Deque<SelectorUpdate> updates = _updates;
                _updates = _updateable;
                _updateable = updates;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("updateable {}", _updateable.size());

            for (SelectorUpdate update : _updateable)
            {
                if (_selector == null)
                    break;
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("update {}", update);
                    update.update(_selector);
                }
                catch (Throwable ex)
                {
                    LOG.warn(ex);
                }
            }
            _updateable.clear();

            Selector selector;
            int updates;
            synchronized (ManagedSelector.this)
            {
                updates = _updates.size();
                _selecting = updates == 0;
                selector = _selecting ? null : _selector;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("updates {}", updates);

            if (selector != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("wakeup on updates {}", this);
                selector.wakeup();
            }
        }

        private boolean select()
        {
            try
            {
                Selector selector = _selector;
                if (selector != null && selector.isOpen())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} waiting with {} keys", selector, selector.keys().size());
                    int selected = selector.select();
                    if (selected == 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Selector {} woken with none selected", selector);

                        if (Thread.interrupted() && !isRunning())
                            throw new ClosedSelectorException();

                        if (FORCE_SELECT_NOW)
                            selected = selector.selectNow();
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} woken up from select, {}/{}/{} selected", selector, selected, selector.selectedKeys().size(), selector.keys().size());

                    int updates;
                    synchronized (ManagedSelector.this)
                    {
                        // finished selecting
                        _selecting = false;
                        updates = _updates.size();
                    }

                    _keys = selector.selectedKeys();
                    _cursor = _keys.isEmpty() ? Collections.emptyIterator() : _keys.iterator();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} processing {} keys, {} updates", selector, _keys.size(), updates);

                    return true;
                }
            }
            catch (Throwable x)
            {
                IO.close(_selector);
                _selector = null;

                if (isRunning())
                {
                    LOG.warn("Fatal select() failure", x);
                    onSelectFailed(x);
                }
                else
                {
                    LOG.warn(x.toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug(x);
                }
            }
            return false;
        }

        private Runnable processSelected()
        {
            while (_cursor.hasNext())
            {
                SelectionKey key = _cursor.next();
                Object attachment = key.attachment();
                SelectableChannel channel = key.channel();
                if (key.isValid())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("selected {} {} {} ", safeReadyOps(key), key, attachment);
                    try
                    {
                        if (attachment instanceof Selectable)
                        {
                            // Try to produce a task
                            Runnable task = ((Selectable)attachment).onSelected();
                            if (task != null)
                                return task;
                        }
                        else if (key.isConnectable())
                        {
                            processConnect(key, (Connect)attachment);
                        }
                        else
                        {
                            throw new IllegalStateException("key=" + key + ", att=" + attachment + ", iOps=" + safeInterestOps(key) + ", rOps=" + safeReadyOps(key));
                        }
                    }
                    catch (CancelledKeyException x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Ignoring cancelled key for channel {}", channel);
                        IO.close(attachment instanceof EndPoint ? (EndPoint)attachment : channel);
                    }
                    catch (Throwable x)
                    {
                        LOG.warn("Could not process key for channel {}", channel, x);
                        IO.close(attachment instanceof EndPoint ? (EndPoint)attachment : channel);
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector loop ignoring invalid key for channel {}", channel);
                    IO.close(attachment instanceof EndPoint ? (EndPoint)attachment : channel);
                }
            }
            return null;
        }

        private void updateKeys()
        {
            // Do update keys for only previously selected keys.
            // This will update only those keys whose selection did not cause an
            // updateKeys update to be submitted.
            for (SelectionKey key : _keys)
            {
                Object attachment = key.attachment();
                if (attachment instanceof Selectable)
                    ((Selectable)attachment).updateKey();
            }
            _keys.clear();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }

    /**
     * A selector update to be done when the selector has been woken.
     */
    public interface SelectorUpdate
    {
        void update(Selector selector);
    }

    private class Start implements SelectorUpdate
    {
        private final CountDownLatch _started = new CountDownLatch(1);

        @Override
        public void update(Selector selector)
        {
            ManagedSelector.this._started.set(true);
            _started.countDown();
        }
    }

    private static class DumpKeys implements SelectorUpdate
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private List<String> keys;

        @Override
        public void update(Selector selector)
        {
            Set<SelectionKey> selectionKeys = selector.keys();
            List<String> list = new ArrayList<>(selectionKeys.size());
            for (SelectionKey key : selectionKeys)
            {
                if (key != null)
                    list.add(String.format("SelectionKey@%x{i=%d}->%s", key.hashCode(), safeInterestOps(key), key.attachment()));
            }
            keys = list;
            latch.countDown();
        }

        public List<String> get(long timeout, TimeUnit unit)
        {
            try
            {
                latch.await(timeout, unit);
            }
            catch (InterruptedException x)
            {
                LOG.ignore(x);
            }
            return keys;
        }
    }

    class Acceptor implements SelectorUpdate, Selectable, Closeable
    {
        private final SelectableChannel _channel;
        private SelectionKey _key;

        Acceptor(SelectableChannel channel)
        {
            _channel = channel;
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                _key = _channel.register(selector, SelectionKey.OP_ACCEPT, this);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} acceptor={}", this, _channel);
            }
            catch (Throwable x)
            {
                IO.close(_channel);
                LOG.warn(x);
            }
        }

        @Override
        public Runnable onSelected()
        {
            SelectableChannel channel = null;
            try
            {
                while (true)
                {
                    channel = _selectorManager.doAccept(_channel);
                    if (channel == null)
                        break;
                    _selectorManager.accepted(channel);
                }
            }
            catch (Throwable x)
            {
                LOG.warn("Accept failed for channel {}", channel, x);
                IO.close(channel);
            }
            return null;
        }

        @Override
        public void updateKey()
        {
        }

        @Override
        public void close() throws IOException
        {
            SelectionKey key = _key;
            if (key != null)
                key.cancel();
        }
    }

    class Accept implements SelectorUpdate, Runnable, Closeable
    {
        private final SelectableChannel channel;
        private final Object attachment;
        private SelectionKey key;

        Accept(SelectableChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            _selectorManager.onAccepting(channel);
        }

        @Override
        public void close()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("closed accept of {}", channel);
            IO.close(channel);
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                key = channel.register(selector, 0, attachment);
                execute(this);
            }
            catch (Throwable x)
            {
                IO.close(channel);
                _selectorManager.onAcceptFailed(channel, x);
                if (LOG.isDebugEnabled())
                    LOG.debug(x);
            }
        }

        @Override
        public void run()
        {
            try
            {
                createEndPoint(channel, key);
                _selectorManager.onAccepted(channel);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(x);
                failed(x);
            }
        }

        protected void failed(Throwable failure)
        {
            IO.close(channel);
            LOG.warn(String.valueOf(failure));
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            _selectorManager.onAcceptFailed(channel, failure);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), channel);
        }
    }

    class Connect implements SelectorUpdate, Runnable
    {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final SelectableChannel channel;
        private final Object attachment;
        private final Scheduler.Task timeout;

        Connect(SelectableChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            this.timeout = ManagedSelector.this._selectorManager.getScheduler().schedule(this, ManagedSelector.this._selectorManager.getConnectTimeout(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                channel.register(selector, SelectionKey.OP_CONNECT, this);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        @Override
        public void run()
        {
            if (_selectorManager.isConnectionPending(channel))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Channel {} timed out while connecting, closing it", channel);
                failed(new SocketTimeoutException("Connect Timeout"));
            }
        }

        public void failed(Throwable failure)
        {
            if (failed.compareAndSet(false, true))
            {
                timeout.cancel();
                IO.close(channel);
                ManagedSelector.this._selectorManager.connectionFailed(channel, failure, attachment);
            }
        }

        @Override
        public String toString()
        {
            return String.format("Connect@%x{%s,%s}", hashCode(), channel, attachment);
        }
    }

    private class CloseConnections implements SelectorUpdate
    {
        private final Set<Closeable> _closed;
        private final CountDownLatch _complete = new CountDownLatch(1);

        private CloseConnections()
        {
            this(null);
        }

        private CloseConnections(Set<Closeable> closed)
        {
            _closed = closed;
        }

        @Override
        public void update(Selector selector)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Closing {} connections on {}", selector.keys().size(), ManagedSelector.this);
            for (SelectionKey key : selector.keys())
            {
                if (key != null && key.isValid())
                {
                    Closeable closeable = null;
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                    {
                        EndPoint endPoint = (EndPoint)attachment;
                        Connection connection = endPoint.getConnection();
                        if (connection != null)
                            closeable = connection;
                        else
                            closeable = endPoint;
                    }

                    if (closeable != null)
                    {
                        if (_closed == null)
                        {
                            IO.close(closeable);
                        }
                        else if (!_closed.contains(closeable))
                        {
                            _closed.add(closeable);
                            IO.close(closeable);
                        }
                    }
                }
            }
            _complete.countDown();
        }
    }

    private class StopSelector implements SelectorUpdate
    {
        private final CountDownLatch _stopped = new CountDownLatch(1);

        @Override
        public void update(Selector selector)
        {
            for (SelectionKey key : selector.keys())
            {
                // Key may be null when using the UnixSocket selector.
                if (key == null)
                    continue;
                Object attachment = key.attachment();
                if (attachment instanceof Closeable)
                    IO.close((Closeable)attachment);
            }
            _selector = null;
            IO.close(selector);
            _stopped.countDown();
        }
    }

    private final class CreateEndPoint implements Runnable
    {
        private final Connect _connect;
        private final SelectionKey _key;

        private CreateEndPoint(Connect connect, SelectionKey key)
        {
            _connect = connect;
            _key = key;
        }

        @Override
        public void run()
        {
            try
            {
                createEndPoint(_connect.channel, _key);
            }
            catch (Throwable failure)
            {
                IO.close(_connect.channel);
                LOG.warn(String.valueOf(failure));
                if (LOG.isDebugEnabled())
                    LOG.debug(failure);
                _connect.failed(failure);
            }
        }

        @Override
        public String toString()
        {
            return String.format("CreateEndPoint@%x{%s}", hashCode(), _connect);
        }
    }

    private class DestroyEndPoint implements Runnable, Closeable
    {
        private final EndPoint endPoint;

        public DestroyEndPoint(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Destroyed {}", endPoint);
            Connection connection = endPoint.getConnection();
            if (connection != null)
                _selectorManager.connectionClosed(connection);
            ManagedSelector.this.endPointClosed(endPoint);
        }

        @Override
        public void close()
        {
            run();
        }
    }
}
