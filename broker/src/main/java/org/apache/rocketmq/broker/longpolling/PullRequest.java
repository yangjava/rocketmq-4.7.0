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
package org.apache.rocketmq.broker.longpolling;

import io.netty.channel.Channel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.MessageFilter;

// RocketMQ轮询机制由两个线程共同来完成,PullRequestHoldService:每隔5S重试一次。DefaultMessageStore#ReputMessageService，每处理一次重新拉取，Thread.sleep(1),继续下一次检查。
public class PullRequest {
    // 请求命令
    private final RemotingCommand requestCommand;
    // 网络连接，通过该通道向客户端返回响应结果。
    private final Channel clientChannel;
    // 超时时长
    private final long timeoutMillis;
    // 挂起开始时间戳,如果当前系统>=(timeoutMills+suspendTimestamp)表示已超时。
    private final long suspendTimestamp;
    // 带拉取消息队列偏移量
    private final long pullFromThisOffset;
    // 订阅信息。
    private final SubscriptionData subscriptionData;
    // 消息过滤器
    private final MessageFilter messageFilter;

    public PullRequest(RemotingCommand requestCommand, Channel clientChannel, long timeoutMillis, long suspendTimestamp,
        long pullFromThisOffset, SubscriptionData subscriptionData,
        MessageFilter messageFilter) {
        this.requestCommand = requestCommand;
        this.clientChannel = clientChannel;
        this.timeoutMillis = timeoutMillis;
        this.suspendTimestamp = suspendTimestamp;
        this.pullFromThisOffset = pullFromThisOffset;
        this.subscriptionData = subscriptionData;
        this.messageFilter = messageFilter;
    }

    public RemotingCommand getRequestCommand() {
        return requestCommand;
    }

    public Channel getClientChannel() {
        return clientChannel;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public long getSuspendTimestamp() {
        return suspendTimestamp;
    }

    public long getPullFromThisOffset() {
        return pullFromThisOffset;
    }

    public SubscriptionData getSubscriptionData() {
        return subscriptionData;
    }

    public MessageFilter getMessageFilter() {
        return messageFilter;
    }
}
