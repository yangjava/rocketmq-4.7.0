# 阅读环境准备

工欲善其事,必先利其器。想要阅读RocketMQ源码，首先需要确定阅读的源码版本，版本不同，其功能特性会存在区别，功能的实现细节也会存在区别。另外，还要配置RocketMQ的运行环境变量。本文主要从以下三个方面讲述阅读RocketMQ源码的准备工作：

1. 下载版本为4.7.0的RocketMQ源码
2. 配置RocketMQ启动的环境变量
3. 启动namesrv和broker

### 下载版本为4.7.0的RocketMQ源码

官方仓库 https://github.com/apache/rocketmq

首先在github上下载版本为4.7.0的RocketMQ，下载地址如下：

```text
https://github.com/apache/rocketmq/tree/rocketmq-all-4.7.0
```

源码的目录结构和模块的作用如下：

```text
rocketmq-rocketmq-all-4.7.0
├─acl                  #用户权限、安全、验证相关模块
├─broker               # Broker主要负责消息的存储、投递和查询以及服务高可用保证，
├─client               # 生产者、消费者、管理等客户端相关模块
├─common               # 公用数据结构等
├─distribution         # 分布式集群相关的脚本以及配置文件，编译模块，编译输出等
├─docs                 # 文档
├─example              # 示例，比如生产者和消费者
├─filter               # 有关消息过滤相关的功能模块
├─logappender          # 各种日志的追加器
├─logging              # 日志功能
├─namesrv              # 简单注册中心，路由管理以及Broker管理
├─openmessaging        # 对外提供服务
├─remoting             # 网络通信相关模块，远程调用接口，封装Netty底层通信
├─srvutil              # namesrc 模块的相关工具，提供一些公用的工具方法，比如解析命令行参数
├─store                # 消息存储相关模块
├─test                 # 测试相关模块
└─tools                # 工具类模块，命令行管理工具，如mqadmin工具
```

RocketMQ源码主要的模块包括namesrv、broker、client、remoting、store，源码分析主要也是涉及到这几个模块。阅读RocketMQ源码前，首先阅读下docs文档，了解RocketMQ的基本概念、架构以及设计思路，这一步是非常重要的。然后将example模块的案例看看，了解下RocketMQ的基本使用，阅读完这两个模块，对阅读RocketMQ源码奠定了基础。

### 配置RocketMQ启动的环境变量

创建一个文件目录/RocketMQ，并在/RocketMQ文件目录下创建conf、logs以及store是三个目录，并将distribution模块下的/conf的broker.conf、logback_broker.xml、logback_namesrv.xml文件拷贝在/RocketMQ/conf目录下。具体的目录形式如下：

```text
D:（D盘，根据自己需求在某盘创建文件）
├─RocketMQ                  
│ ├─conf
│ │ │ ├─broker.conf
│ │ │ ├─logback_broker.xml
│ │ │ ├─logback_namesrv.xml
│ ├─logs
│ ├─store
```

创建并拷贝好上述文件以后，接下来需要修改broker.conf、logback_broker.xml、logback_namesrv.xml文件的内容，broker.conf的修改内容如下：

```text
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH

# 这是存储路径，你设置为你的rocketmq运行目录的store子目录
storePathRootDir=D:\RocketMQ\store

# 这是commitLog的存储路径
storePathCommitLog=D:\RocketMQ\store\commitlog

# consume queue文件的存储路径
storePathConsumeQueue=D:\RocketMQ\store\consumequeue

# 消息索引文件的存储路径
storePathIndex=D:\RocketMQ\store\index

# checkpoint文件的存储路径
storeCheckpoint=D:\RocketMQ\store\checkpoint

# abort文件的存储路径
abortFile=D:\RocketMQ\abort
```

修改以后保存broker.conf，对logback_namesrv.xml和logback_broker.xml文件进行修改，将文件中所有**${user.home}**替换成**D:\rocketMqConfig**。

### 启动namesrv和broker

broker的启动因为依赖namesrv，所以首先启动namesrv。在namesrv模块中找到NamesrvStartup类，启动NamesrvStartup类的main方法。当你启动NamesrvStartup的main方法的时候，你会发现启动失败，是因为还需要配置下启动项，主要在如下配置**ROCKETMQ_HOME=D:\RocketMQ**，D:\RocketMQ文件就是你在上述的配置目录。

```text
ROCKETMQ_HOME=D:\RocketMQ
```

namesrv启动环境变量配置截图如下图所示：

![NamesrvStartup](png/NamesrvStartup.png)

然后就可以启动namesrv了，启动的时候可以打断点进行跟踪namesrv启动的过程，启动成功以后控制台会显示如下信息：

```text
The Name Server boot success. serializeType=JSON
```

命令行中输入 telnet 127.0.0.1 9876 ，看看是否能连接上 RocketMQ Namesrv 。

broker的启动也是跟namesrv启动一样，在broker模块找到BrokerStartup类，启动BrokerStartup的main方法。也是需要配置环境变量的，如下：

```text
ROCKETMQ_HOME=D:\RocketMQ;NAMESRV_ADDR=127.0.0.1:9876
```

broker启动环境变量配置截图如下图所示：

![BrokerStartup](png/BrokerStartup.png)

当你看到控制台显示如下信息时，说明broker也启动成功了。

```text
The broker[DESKTOP-DFA24F11, 10.1.70.191:10911] boot success. serializeType=JSON and name server is 127.0.0.1:9876
```

妥妥的，原来 RocketMQ Broker 已经启动完成，并且注册到 RocketMQ Namesrv 上。

命令行中输入 telnet 127.0.0.1 10911 ，看看是否能连接上 RocketMQ Broker 。

当准备好以上条件以后，就万事俱备了，就可以开始阅读RocketMQ源代码。