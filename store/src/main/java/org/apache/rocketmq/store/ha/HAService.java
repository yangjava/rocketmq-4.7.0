/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.ha;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.CommitLog;
import org.apache.rocketmq.store.DefaultMessageStore;
// 主从同步核心实现类
public class HAService {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    // Master 维护的与Slave的连接数。
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    // Master 与 Slave 的连接集合
    private final List<HAConnection> connectionList = new LinkedList<>();
    // Master接收Slave发送的连接请求线程实现类。
    private final AcceptSocketService acceptSocketService;
    // 默认消息存储。
    private final DefaultMessageStore defaultMessageStore;
    // 同步等待实现。
    private final WaitNotifyObject waitNotifyObject = new WaitNotifyObject();
    // Master推送给slave的最大的位移。
    private final AtomicLong push2SlaveMaxOffset = new AtomicLong(0);
    // 判断主从同步复制是否完成的线程实现类。
    private final GroupTransferService groupTransferService;
    // HA客户端实现，Slave端网络的实现类。
    private final HAClient haClient;

    public HAService(final DefaultMessageStore defaultMessageStore) throws IOException {
        this.defaultMessageStore = defaultMessageStore;
        this.acceptSocketService =
            new AcceptSocketService(defaultMessageStore.getMessageStoreConfig().getHaListenPort());
        this.groupTransferService = new GroupTransferService();
        this.haClient = new HAClient();
    }

    public void updateMasterAddress(final String newAddr) {
        if (this.haClient != null) {
            this.haClient.updateMasterAddress(newAddr);
        }
    }

    public void putRequest(final CommitLog.GroupCommitRequest request) {
        this.groupTransferService.putRequest(request);
    }
    // isSlaveOK判断Slave从服务是否是良好的，Slave从服务器良好的标志是与master的连接数大于0并且是否已经到达需要推送数据的要求了。
    public boolean isSlaveOK(final long masterPutWhere) {
        // 与master的连接数
        boolean result = this.connectionCount.get() > 0;
        // master 推送的位移减去推送给slave的位移是否小于slave 配置的最大落后位移
        result =
            result
                && ((masterPutWhere - this.push2SlaveMaxOffset.get()) < this.defaultMessageStore
                .getMessageStoreConfig().getHaSlaveFallbehindMax());
        return result;
    }

    public void notifyTransferSome(final long offset) {
        for (long value = this.push2SlaveMaxOffset.get(); offset > value; ) {
            boolean ok = this.push2SlaveMaxOffset.compareAndSet(value, offset);
            if (ok) {
                this.groupTransferService.notifyTransferSome();
                break;
            } else {
                value = this.push2SlaveMaxOffset.get();
            }
        }
    }

    public AtomicInteger getConnectionCount() {
        return connectionCount;
    }

    // public void notifyTransferSome() {
    // this.groupTransferService.notifyTransferSome();
    // }

    // HAService类是主从同步的核心类型，最重要的方法就是start方法
    public void start() throws Exception {
        // 开始监听slave连接
        this.acceptSocketService.beginAccept();
        // 监听slave 连接创建
        this.acceptSocketService.start();
        // 判断主从同步是否结束
        this.groupTransferService.start();
        // ha客户端启动
        this.haClient.start();
    }

    public void addConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.add(conn);
        }
    }

    public void removeConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.remove(conn);
        }
    }

    public void shutdown() {
        this.haClient.shutdown();
        this.acceptSocketService.shutdown(true);
        this.destroyConnections();
        this.groupTransferService.shutdown();
    }

    public void destroyConnections() {
        synchronized (this.connectionList) {
            for (HAConnection c : this.connectionList) {
                c.shutdown();
            }

            this.connectionList.clear();
        }
    }

    public DefaultMessageStore getDefaultMessageStore() {
        return defaultMessageStore;
    }

    public WaitNotifyObject getWaitNotifyObject() {
        return waitNotifyObject;
    }

    public AtomicLong getPush2SlaveMaxOffset() {
        return push2SlaveMaxOffset;
    }

    /**
     * Listens to slave connections to create {@link HAConnection}.
     */

    // AcceptSocketService类是HAService类的内部类，是Master用来监听Slave请求连接的。
    class AcceptSocketService extends ServiceThread {
        private final SocketAddress socketAddressListen;
        private ServerSocketChannel serverSocketChannel;
        private Selector selector;

        public AcceptSocketService(final int port) {
            this.socketAddressListen = new InetSocketAddress(port);
        }

        /**
         * Starts listening to slave connections.
         *
         * @throws Exception If fails.
         */
        public void beginAccept() throws Exception {
            //创建ServerSocketChannel
            this.serverSocketChannel = ServerSocketChannel.open();
            //创建Selector
            this.selector = RemotingUtil.openSelector();
            //设置TCP reuseAddress
            this.serverSocketChannel.socket().setReuseAddress(true);
            //绑定监听套接字（IP、port）slave的ip和port
            this.serverSocketChannel.socket().bind(this.socketAddressListen);
            //设置非阻塞
            this.serverSocketChannel.configureBlocking(false);
            //注册OP_ACCEPT(连接事件)
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown(final boolean interrupt) {
            super.shutdown(interrupt);
            try {
                this.serverSocketChannel.close();
                this.selector.close();
            } catch (IOException e) {
                log.error("AcceptSocketService shutdown exception", e);
            }
        }

        /**
         * {@inheritDoc}
         */
        // AcceptSocketService是线程类，主要处理Slave请求连接的逻辑在run方法
        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    // 选择器每1s处理一次连接就绪事件
                    this.selector.select(1000);
                    Set<SelectionKey> selected = this.selector.selectedKeys();
                    // 遍历所有的连接就绪事件
                    if (selected != null) {
                        for (SelectionKey k : selected) {
                            // 如果事件已经就绪和是连接就绪事件
                            if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                                // 连接通道
                                SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();

                                if (sc != null) {
                                    HAService.log.info("HAService receive new connection, "
                                        + sc.socket().getRemoteSocketAddress());

                                    try {
                                        // 创建HA连接，并且启动，添加连接集合中
                                        HAConnection conn = new HAConnection(HAService.this, sc);
                                        conn.start();
                                        HAService.this.addConnection(conn);
                                    } catch (Exception e) {
                                        log.error("new HAConnection exception", e);
                                        sc.close();
                                    }
                                }
                            } else {
                                log.warn("Unexpected ops in select " + k.readyOps());
                            }
                        }
                        // 清除所有的选择事件
                        selected.clear();
                    }
                } catch (Exception e) {
                    log.error(this.getServiceName() + " service has exception.", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getServiceName() {
            return AcceptSocketService.class.getSimpleName();
        }
    }

    /**
     * GroupTransferService Service
     */
    class GroupTransferService extends ServiceThread {

        private final WaitNotifyObject notifyTransferObject = new WaitNotifyObject();
        private volatile List<CommitLog.GroupCommitRequest> requestsWrite = new ArrayList<>();
        private volatile List<CommitLog.GroupCommitRequest> requestsRead = new ArrayList<>();

        public synchronized void putRequest(final CommitLog.GroupCommitRequest request) {
            synchronized (this.requestsWrite) {
                this.requestsWrite.add(request);
            }
            if (hasNotified.compareAndSet(false, true)) {
                waitPoint.countDown(); // notify
            }
        }

        public void notifyTransferSome() {
            this.notifyTransferObject.wakeup();
        }

        private void swapRequests() {
            List<CommitLog.GroupCommitRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }
        // doWaitTransfer方法对requestsRead进行加锁，并且遍历所有的主从同步结束读请求，一直阻塞到主从同步结束。
        // 首先判断推送给slave的最大的位移是否大于等于下一条消息的起始偏移量，如果小于的话，说明数据还没有同步完，另外同步没有超时，那么就等待1秒，并且一直阻塞。
        // 一直循环判断上面两个条件，只要其中之一不满足，则退出阻塞。退出阻塞，要不就是数据已经同步完成，要不就是数据同步超时。最后设置最终的同步状态。
        private void doWaitTransfer() {
            // 锁住主从同步结束读请求
            synchronized (this.requestsRead) {
                // 如果主从同步结束读请求不为空
                if (!this.requestsRead.isEmpty()) {
                    // 遍历所有主从同步结束读请求
                    for (CommitLog.GroupCommitRequest req : this.requestsRead) {
                        // 推送给slave的最大的位移是否大于等于下一条消息的起始偏移量
                        boolean transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                        // 等待时间等于当前时间加上同步超时时间
                        long waitUntilWhen = HAService.this.defaultMessageStore.getSystemClock().now()
                            + HAService.this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout();
                        // 如果同步给slave的数据还没有完成 && 当前时间小于等待的时间
                        while (!transferOK && HAService.this.defaultMessageStore.getSystemClock().now() < waitUntilWhen) {
                            //等待1秒，并且交换主从同步结束读请求与主从同步结束写请求
                            this.notifyTransferObject.waitForRunning(1000);
                            //推送给slave的最大的位移是否大于等于下一条消息的起始偏移量
                            transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                        }
                        // 如果超时，打印日志
                        if (!transferOK) {
                            log.warn("transfer messsage to slave timeout, " + req.getNextOffset());
                        }
                        // 设置最终的同步状态
                        req.wakeupCustomer(transferOK);
                    }
                    // 清除请去读
                    this.requestsRead.clear();
                }
            }
        }

        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    this.waitForRunning(10);
                    this.doWaitTransfer();
                } catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }

        @Override
        public String getServiceName() {
            return GroupTransferService.class.getSimpleName();
        }
    }
    // HAClient是的作用有两个：向Master汇报已拉取消息的偏移量、处理Master发送给Slave的数据。HAClient线程启动以后就不断处理上面两个流程。
    // HAClient类的作用就是Slave上报自己的最大偏移量，以及处理从master拉取过来的数据并落盘保存起来。
    class HAClient extends ServiceThread {
        private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024 * 4;
        private final AtomicReference<String> masterAddress = new AtomicReference<>();
        private final ByteBuffer reportOffset = ByteBuffer.allocate(8);
        private SocketChannel socketChannel;
        private Selector selector;
        private long lastWriteTimestamp = System.currentTimeMillis();

        private long currentReportedOffset = 0;
        private int dispatchPosition = 0;
        private ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
        private ByteBuffer byteBufferBackup = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);

        public HAClient() throws IOException {
            this.selector = RemotingUtil.openSelector();
        }

        public void updateMasterAddress(final String newAddr) {
            String currentAddr = this.masterAddress.get();
            if (currentAddr == null || !currentAddr.equals(newAddr)) {
                this.masterAddress.set(newAddr);
                log.info("update master address, OLD: " + currentAddr + " NEW: " + newAddr);
            }
        }
        // 判断是否需要向Master汇报已拉取消息偏移量。
        private boolean isTimeToReportOffset() {
            //当前时间减去最后一次写时间
            long interval =
                HAService.this.defaultMessageStore.getSystemClock().now() - this.lastWriteTimestamp;
            //大于发送心跳时间间隔
            boolean needHeart = interval > HAService.this.defaultMessageStore.getMessageStoreConfig()
                .getHaSendHeartbeatInterval();

            return needHeart;
        }

        private boolean reportSlaveMaxOffset(final long maxOffset) {
            //发送8字节的请求
            this.reportOffset.position(0);
            this.reportOffset.limit(8);
            //最大偏移量
            this.reportOffset.putLong(maxOffset);
            this.reportOffset.position(0);
            this.reportOffset.limit(8);
            //如果需要向Master反馈当前拉取偏移量，则向Master发送一个8字节的请求，请求包中包含的数据为当前Broker消息文件的最大偏移量。
            //如果还有剩余空间并且循环次数小于3
            for (int i = 0; i < 3 && this.reportOffset.hasRemaining(); i++) {
                try {
                    //发送上报的偏移量
                    this.socketChannel.write(this.reportOffset);
                } catch (IOException e) {
                    log.error(this.getServiceName()
                        + "reportSlaveMaxOffset this.socketChannel.write exception", e);
                    return false;
                }
            }
            // 最后一次写时间
            lastWriteTimestamp = HAService.this.defaultMessageStore.getSystemClock().now();
            // 是否还有剩余空间
            return !this.reportOffset.hasRemaining();
        }

        private void reallocateByteBuffer() {
            int remain = READ_MAX_BUFFER_SIZE - this.dispatchPosition;
            if (remain > 0) {
                this.byteBufferRead.position(this.dispatchPosition);

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(READ_MAX_BUFFER_SIZE);
                this.byteBufferBackup.put(this.byteBufferRead);
            }

            this.swapByteBuffer();

            this.byteBufferRead.position(remain);
            this.byteBufferRead.limit(READ_MAX_BUFFER_SIZE);
            this.dispatchPosition = 0;
        }

        private void swapByteBuffer() {
            ByteBuffer tmp = this.byteBufferRead;
            this.byteBufferRead = this.byteBufferBackup;
            this.byteBufferBackup = tmp;
        }
        //处理读事件
        private boolean processReadEvent() {
            //读取大小为0的次数
            int readSizeZeroTimes = 0;
            //判断是否还有剩余
            while (this.byteBufferRead.hasRemaining()) {
                try {
                    //读取到缓存中
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    //读取的大小大于0
                    if (readSize > 0) {
                        //重置读取到0字节的次数
                        readSizeZeroTimes = 0;
                        //分发读请求
                        boolean result = this.dispatchReadRequest();
                        if (!result) {
                            log.error("HAClient, dispatchReadRequest error");
                            return false;
                        }
                    } else if (readSize == 0) {
                        //如果连续 3 次从网络通道读取到 0 个字节，则结束本次读，返回 true 。
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        //如果读取到的字节数小于0或发生IO异常，则返回false。
                        log.info("HAClient, processReadEvent read socket < 0");
                        return false;
                    }
                } catch (IOException e) {
                    log.info("HAClient, processReadEvent read socket exception", e);
                    return false;
                }
            }

            return true;
        }
        // dispatchReadRequest方法将读取到的数据一条一条解析，并且落盘保存到本地。但是读取的数据可能不是完整的，所以要判断读取的数据是否完整，消息包括消息头和消息体，消息头12字节长度，包括物理偏移量与消息的长度。首先判断读取到数据的长度是否大于消息头部长度，
        // 如果小于说明读取的数据是不完整的，则判断byteBufferRead是否还有剩余空间，并且将readByteBuffer中剩余的有效数据先复制到readByteBufferBak,然后交换readByteBuffer与readByteBufferBak。
        // 如果读取的数据的长度大于消息头部长度，则判断master和slave的偏移量是否相等，如果slave的最大物理偏移量与master给的偏移量不相等，则返回false。
        // 在后续的处理中，返回false，将会关闭与master的连接。如果读取的数据长度大于等于消息头长读与消息体长度，说明读取的数据是包好完整消息的，将消息体的内容从byteBufferRead中读取出来，并且将消息保存到commitLog文件中。
        private boolean dispatchReadRequest() {
            // 头部长度,物理偏移量与消息的长度
            final int msgHeaderSize = 8 + 4; // phyoffset + size
            // 记录当前byteBufferRead的当前指针
            int readSocketPos = this.byteBufferRead.position();

            while (true) {
                // 当前指针减去本次己处理读缓存区的指针
                int diff = this.byteBufferRead.position() - this.dispatchPosition;
                // 是否大于头部长度，是否包含头部
                if (diff >= msgHeaderSize) {
                    // master的物理偏移量
                    long masterPhyOffset = this.byteBufferRead.getLong(this.dispatchPosition);
                    // 消息的长度
                    int bodySize = this.byteBufferRead.getInt(this.dispatchPosition + 8);
                    // slave 偏移量
                    long slavePhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
                    //如果slave的最大物理偏移量与master给的偏移量不相等，则返回false
                    // 从后面的处理逻辑来看，返回false,将会关闭与master的连接，在Slave本次周期内将不会再参与主从同步了。
                    if (slavePhyOffset != 0) {
                        if (slavePhyOffset != masterPhyOffset) {
                            log.error("master pushed offset not equal the max phy offset in slave, SLAVE: "
                                + slavePhyOffset + " MASTER: " + masterPhyOffset);
                            return false;
                        }
                    }
                    // 包含完整的信息
                    if (diff >= (msgHeaderSize + bodySize)) {
                        // 读取消息到缓冲区
                        byte[] bodyData = new byte[bodySize];
                        this.byteBufferRead.position(this.dispatchPosition + msgHeaderSize);
                        this.byteBufferRead.get(bodyData);
                        // 添加到commit log 文件
                        HAService.this.defaultMessageStore.appendToCommitLog(masterPhyOffset, bodyData);

                        this.byteBufferRead.position(readSocketPos);
                        // 当前的已读缓冲区指针
                        this.dispatchPosition += msgHeaderSize + bodySize;
                        // 上报slave最大的复制偏移量
                        if (!reportSlaveMaxOffsetPlus()) {
                            return false;
                        }

                        continue;
                    }
                }
                // 没有包含完整的消息，
                // 其核心思想是将readByteBuffer中剩余的有效数据先复制到readByteBufferBak,然后交换readByteBuffer与readByteBufferBak。
                if (!this.byteBufferRead.hasRemaining()) {
                    this.reallocateByteBuffer();
                }

                break;
            }

            return true;
        }

        private boolean reportSlaveMaxOffsetPlus() {
            boolean result = true;
            long currentPhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
            if (currentPhyOffset > this.currentReportedOffset) {
                this.currentReportedOffset = currentPhyOffset;
                result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                if (!result) {
                    this.closeMaster();
                    log.error("HAClient, reportSlaveMaxOffset error, " + this.currentReportedOffset);
                }
            }

            return result;
        }
        // 主动连接Master，如果连接不成功，则等待5秒，否则进行下一步的处理。
        private boolean connectMaster() throws ClosedChannelException {
            if (null == socketChannel) {
                String addr = this.masterAddress.get();
                if (addr != null) {

                    SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
                    if (socketAddress != null) {
                        //创建连接slave 和 master的连接
                        this.socketChannel = RemotingUtil.connect(socketAddress);
                        if (this.socketChannel != null) {
                            //注册OP_READ(网络读事件)
                            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
                        }
                    }
                }
                // 设置当前的复制进度为commitlog文件的最大偏移量
                this.currentReportedOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
                // 最后一次写的时间
                this.lastWriteTimestamp = System.currentTimeMillis();
            }

            return this.socketChannel != null;
        }

        private void closeMaster() {
            if (null != this.socketChannel) {
                try {

                    SelectionKey sk = this.socketChannel.keyFor(this.selector);
                    if (sk != null) {
                        sk.cancel();
                    }

                    this.socketChannel.close();

                    this.socketChannel = null;
                } catch (IOException e) {
                    log.warn("closeMaster exception. ", e);
                }

                this.lastWriteTimestamp = 0;
                this.dispatchPosition = 0;

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(READ_MAX_BUFFER_SIZE);

                this.byteBufferRead.position(0);
                this.byteBufferRead.limit(READ_MAX_BUFFER_SIZE);
            }
        }

        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    if (this.connectMaster()) {

                        if (this.isTimeToReportOffset()) {
                            boolean result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                            if (!result) {
                                this.closeMaster();
                            }
                        }
                        // 进行事件选择， 其执行间隔为 1s 。
                        this.selector.select(1000);
                        //处理读事件
                        boolean ok = this.processReadEvent();
                        //处理读事件不ok，关闭与master的连接
                        if (!ok) {
                            this.closeMaster();
                        }
                        //上报slave最大的偏移量
                        if (!reportSlaveMaxOffsetPlus()) {
                            continue;
                        }

                        long interval =
                            HAService.this.getDefaultMessageStore().getSystemClock().now()
                                - this.lastWriteTimestamp;
                        if (interval > HAService.this.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaHousekeepingInterval()) {
                            log.warn("HAClient, housekeeping, found this connection[" + this.masterAddress
                                + "] expired, " + interval);
                            this.closeMaster();
                            log.warn("HAClient, master not response some time, so close connection");
                        }
                    } else {
                        this.waitForRunning(1000 * 5);
                    }
                } catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                    this.waitForRunning(1000 * 5);
                }
            }

            log.info(this.getServiceName() + " service end");
        }
        // private void disableWriteFlag() {
        // if (this.socketChannel != null) {
        // SelectionKey sk = this.socketChannel.keyFor(this.selector);
        // if (sk != null) {
        // int ops = sk.interestOps();
        // ops &= ~SelectionKey.OP_WRITE;
        // sk.interestOps(ops);
        // }
        // }
        // }
        // private void enableWriteFlag() {
        // if (this.socketChannel != null) {
        // SelectionKey sk = this.socketChannel.keyFor(this.selector);
        // if (sk != null) {
        // int ops = sk.interestOps();
        // ops |= SelectionKey.OP_WRITE;
        // sk.interestOps(ops);
        // }
        // }
        // }

        @Override
        public String getServiceName() {
            return HAClient.class.getSimpleName();
        }
    }
}
