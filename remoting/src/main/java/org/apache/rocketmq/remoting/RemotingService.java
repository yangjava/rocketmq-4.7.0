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
// 首先通信的两端分为客户端和服务端，客户端和服务端都有启动、关闭的方法，为了在处理请求之前和返回响应之后做一些事情，采用钩子机制来拓展，所以还需要一个注册钩子的方法，定义的客户端和服务端的公共接口
public interface RemotingService {
    //启动
    void start();
    //关闭
    void shutdown();
    //注册钩子
    void registerRPCHook(RPCHook rpcHook);
}
