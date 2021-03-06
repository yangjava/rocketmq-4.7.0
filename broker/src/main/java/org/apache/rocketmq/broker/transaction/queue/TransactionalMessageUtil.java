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
package org.apache.rocketmq.broker.transaction.queue;

import org.apache.rocketmq.common.MixAll;

import java.nio.charset.Charset;

public class TransactionalMessageUtil {
    public static final String REMOVETAG = "d";
    public static Charset charset = Charset.forName("utf-8");

    public static String buildOpTopic() {
        return MixAll.RMQ_SYS_TRANS_OP_HALF_TOPIC;
    }
    // buildHalfTopic方法就是获取RMQ_SYS_TRANS_HALF_TOPIC。
    // 上述将半消息的topic替换为RMQ_SYS_TRANS_HALF_TOPIC，因为消费者没有订阅RMQ_SYS_TRANS_HALF_TOPIC，所以半消息对消费者不可见，这就是半消息对消费者不可见的源码分析。
    public static String buildHalfTopic() {
        return MixAll.RMQ_SYS_TRANS_HALF_TOPIC;
    }

    public static String buildConsumerGroup() {
        return MixAll.CID_SYS_RMQ_TRANS;
    }

}
