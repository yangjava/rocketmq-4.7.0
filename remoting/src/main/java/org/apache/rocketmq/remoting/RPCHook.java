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

package org.apache.rocketmq.remoting;

import org.apache.rocketmq.remoting.protocol.RemotingCommand;
// 钩子接口RPCHook有两个方法，分别是doBeforeRequest和doAfterResponse，doBeforeRequest方法在请求之前调用，doAfterResponse在响应之后调用。
// 参数remoteAddr是请求地址，request是请求参数，response是响应参数，类型是RemotingCommand。RPCHook的作用是更好的拓展性，允许用户在请求之前和响应之后做一些事情。
public interface RPCHook {
    // 在处理请求之前调用
    void doBeforeRequest(final String remoteAddr, final RemotingCommand request);
    // 在返回响应之后调用
    void doAfterResponse(final String remoteAddr, final RemotingCommand request,
        final RemotingCommand response);
}
