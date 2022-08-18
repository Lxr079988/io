# Redis

## 1.Redis的IO模型

### 网络IO模型发展史

#### 阻塞IO

我们经常说的阻塞IO其实分为两种，一种是**单线程阻塞**，一种是**多线程阻塞**。这里面其实有两个概念，阻塞和线程。

- 阻塞：指调用结果返回之前，当前线程会被挂起，==调用线程只有在得到结果之后才会返回；==
- 线程：系统调用的线程个数。


像建立连接、读、写都涉及到系统调用，本身是一个阻塞的操作。

##### **单线程阻塞**

服务端单线程来处理，当客户端请求来临时，服务端用主线程来处理连接、读取、写入等操作。

以下用代码模拟了单线程的阻塞模式。

```java
public class BioTest {

    public static void main(String[] args) throws IOException {
        // 1创建服务器套接字
        ServerSocket server=new ServerSocket(8081);
        while(true) {
            // 2监听连接事件，阻塞
            Socket socket=server.accept();
            System.out.println("accept port:"+socket.getPort());
            // 3获取输入流
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
            String inData=null;
            try {
                // 4一次性从缓冲区中将内容全部读取进来
                while ((inData = in.readLine()) != null) {
                    System.out.println("client port:"+socket.getPort());
                    System.out.println("input data:"+inData);
                    if("close".equals(inData)) {
                        socket.close();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
```

我们准备用两个客户端同时发起连接请求、来模拟单线程阻塞模式的现象。同时发起连接，通过服务端日志，我们发现此时服务端只接受了其中一个连接，主线程被阻塞在上一个连接的read方法上。

![2.png](http://dockone.io/uploads/article/20211128/867c51f826ab2216004d1999915185e0.png)



![3.png](http://dockone.io/uploads/article/20211128/90dda6d3b527e95b7e8dd2ab0e04ffdf.png)

这个时候只有一个连接能发送信息。

我们尝试关闭第一个连接，看第二个连接的情况，我们希望看到的现象是，主线程返回，新的客户端连接被接受。

![4.png](http://dockone.io/uploads/article/20211128/7d97855d6d0eccbbea0f54885ed7763a.png)

从日志中发现，在第一个连接被关闭后，**第二个连接的请求被处理了，也就是说第二个连接请求在排队，直到主线程被唤醒，才能接收下一个请求**，符合我们的预期。

***此时不仅要问，为什么呢？\***

主要原因在于**accept、read、write**三个函数都是**阻塞**的，**主线程在系统调用的时候，线程是被阻塞的，其他客户端的连接无法被响应。**

通过以上流程，我们很容易发现这个过程的缺陷，服务器每次只能处理一个连接请求，==CPU没有得到充分利用，性能比较低==。如何充分利用CPU的多核特性呢？自然而然的想到了——**多线程逻辑**。

##### **多线程阻塞**

BIO多线程：

```java
public class MutilThreadBioTest {

    public static void main(String[] args) throws IOException {
        // 1创建服务器套接字
        final ServerSocket server = new ServerSocket(8081);
        while (true) {
            // 2不断创建线程并启动
            new Thread(new Runnable() {
                public void run() {
                    Socket socket = null;
                    try {
                        // 3监听连接事件，阻塞
                        socket = server.accept();
                        System.out.println("accept port:" + socket.getPort() + Thread.currentThread().getName());
                        // 4获取输入流
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        String inData = null;
                        // 5一次性从缓冲区中将内容全部读取进来
                        while ((inData = in.readLine()) != null) {
                            System.out.println("client port:" + socket.getPort() + Thread.currentThread().getName());
                            System.out.println("input data:" + inData + Thread.currentThread().getName());
                            if ("close".equals(inData)) {
                                socket.close();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {

                    }
                }
            }).start();
        }
    }
}
```

同样，我们并行发起两个请求。

![5.png](http://dockone.io/uploads/article/20211128/2a4eea579310dac0856f54dfadb8b007.png)

两个请求，都被接受，服务端新增两个线程来处理客户端的连接和后续请求。

![6.png](http://dockone.io/uploads/article/20211128/1aaec96b0743c4ba3c51d5edd6f1ba7c.png)

![7.png](http://dockone.io/uploads/article/20211128/a0a94cb979f041b477cbda7fe33de493.png)

最后会报oom没有资源创建新的线程了，Exception in thread "main" java.lang.OutOfMemoryError: unable to create new native thread

我们用多线程解决了，服务器同时只能处理一个请求的问题，但同时又带来了一个问题，**如果客户端连接比较多时，服务端会创建大量的线程来处理请求，但线程本身是比较耗资源的，创建、上下文切换都比较耗资源**，又如何去解决呢？

#### 非阻塞

##### 单纯非阻塞模式

如果我们把所有的Socket（文件句柄，后续用Socket来代替fd的概念，尽量减少概念，减轻阅读负担）都放到队列里，**只用一个线程来轮训所有的Socket的状态**，如果准备好了就把它拿出来，是不是就减少了服务端的线程数呢？

一起看下代码，**单纯非阻塞模式，我们基本上不用**，为了演示逻辑，我们模拟了相关代码如下：

```java
public class NioTest {

    public static void main(String[] args) throws IOException {
        final ServerSocket server = new ServerSocket(8082);
        // 1ServerSocket 的 accept() 调用将仅阻塞此时间量
        server.setSoTimeout(1000);
        List<Socket> sockets = new ArrayList<Socket>();
        while (true) {
            Socket socket = null;
            try {
                socket = server.accept();
                // 2Socket 关联的 InputStream 的 read() 调用将仅阻塞此时间量
                socket.setSoTimeout(500);
                sockets.add(socket);
                System.out.println("accept client port:" + socket.getPort() + Thread.currentThread().getName());
            } catch (SocketTimeoutException e) {
                System.out.println("accept timeout" + Thread.currentThread().getName());
            }
            // 3模拟非阻塞：轮询已连接的socket，每个socket等待10MS，有数据就处理，无数据就返回，继续轮询
            if (!sockets.isEmpty()) {
                for (Socket socketTemp : sockets) {
                    try {
                        BufferedReader in = new BufferedReader(new InputStreamReader(socketTemp.getInputStream()));
                        String inData = null;
                        while ((inData = in.readLine()) != null) {
                            System.out.println("input data client port:" + socketTemp.getPort() + Thread.currentThread().getName());
                            System.out.println("input data client port:" + socketTemp.getPort() + "data:" + inData + Thread.currentThread().getName());
                            if ("close".equals(inData)) {
                                socketTemp.close();
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("input client loop" + socketTemp.getPort() + Thread.currentThread().getName());
                    }
                }
            }
        }

    }
}
```

系统初始化，等待连接：![8.png](http://dockone.io/uploads/article/20211128/b330ba57acbb96df569fd18f6a750613.png)

发起两个客户端连接，**线程开始轮询两个连接中是否有数据**：

![9.png](http://dockone.io/uploads/article/20211128/d5095c5edf45b44edf5e0055aa61a73c.png)

两个连接分别输入数据后，轮询线程发现有数据准备好了，开始相关的逻辑处理（单线程、多线程都可）：

![10.png](http://dockone.io/uploads/article/20211128/88fc5670d961827ac83ab3d9c2d85932.png)

再用一张流程图辅助解释下（系统实际采用文件句柄，此时用Socket来代替，方便大家理解）：

![11.png](http://dockone.io/uploads/article/20211128/2df32af0dae44a78897e5acdf4290a49.png)

服务端专门有一个线程来负责轮询所有的Socket，来确认操作系统是否完成了相关事件，如果有则返回处理，如果无继续轮询，大家一起来思考下？此时又带来了什么问题呢。

**CPU的空转、系统调用（每次轮询到涉及到一次系统调用，通过内核命令来确认数据是否准备好），造成资源的浪费**，那有没有一种机制，来解决这个问题呢？

##### IO多路复用

服务端没有专门的线程来做轮询操作（应用程序端非内核），而是**由事件来触发，当有相关读、写、连接事件到来时，主动唤起服务端线程来进行相关逻辑处理**。模拟了相关代码如下:

```java
public class NioServer {

    private static Charset charset = Charset.forName("UTF-8");

    public static void main(String[] args) {
        try {
            // 1选择器，不过根据我们详细介绍的Selector的岗位职责，您可以把它称之为“轮询代理器”、“事件订阅器”、“channel容器管理机”都行。
            Selector selector = Selector.open();
            // 2通道，被建立的一个应用程序和操作系统交互事件、传递内容的渠道(注意是连接到操作系统)
            ServerSocketChannel chanel = ServerSocketChannel.open();
            chanel.bind(new InetSocketAddress(8083));
            // 2设置非阻塞
            chanel.configureBlocking(false);
            // 3注意、服务器通道只能注册SelectionKey.OP_ACCEPT事件
            chanel.register(selector, SelectionKey.OP_ACCEPT);

            while (true) {
                // 4如果条件成立，说明本次询问selector，并没有获取到任何准备好的、感兴趣的事件,没有事件会被阻塞
                int select = selector.select();
                if (select == 0) {
                    System.out.println("select loop");
                    continue;
                }
                System.out.println("os data ok");

                // 5这里就是本次询问操作系统，所获取到的“所关心的事件”的事件类型(每一个通道都是独立的)
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectionKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey selectionKey = iterator.next();

                    if (selectionKey.isAcceptable()) {
                        /*
                         * 当server socket channel通道已经准备好，就可以从server socket channel中获取socketchannel了
                         * 拿到socket channel后，要做的事情就是马上到selector注册这个socket channel感兴趣的事情。
                         * 否则无法监听到这个socket channel到达的数据
                         * */
                        ServerSocketChannel server = (ServerSocketChannel) selectionKey.channel();
                        SocketChannel client = server.accept();
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ);
                        //继续可以接收连接事件
                        selectionKey.interestOps(SelectionKey.OP_ACCEPT);
                    } else if (selectionKey.isReadable()) {
                        //得到SocketChannel
                        SocketChannel client = (SocketChannel) selectionKey.channel();
                        //定义缓冲区
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        StringBuilder content = new StringBuilder();
                        while (client.read(buffer) > 0) {
                            //将缓存区从写状态切换为读状态(实际上这个方法是读写模式互切换)。
                            buffer.flip();
                            content.append(charset.decode(buffer));
                        }
                        System.out.println("client port:" + client.getRemoteAddress().toString() + ",input data: " + content.toString());
                        //清空已经读取的缓存，并从新切换为写状态(这里要注意clear()和capacity()两个方法的区别)
                        buffer.clear();
                    }

                    //这个已经处理的readyKey一定要移除。如果不移除，就会一直存在在selector.selectedKeys集合中
                    //待到下一次selector.select() > 0时，这个readyKey又会被处理一次
                    iterator.remove();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

同时创建两个连接：

[![12.jpg](http://dockone.io/uploads/article/20211128/fa3651b9a2af71f30efdb38493e5bab2.jpg)](http://dockone.io/uploads/article/20211128/fa3651b9a2af71f30efdb38493e5bab2.jpg)


两个连接无阻塞的被创建：

[![13.png](http://dockone.io/uploads/article/20211128/3fb4b5ccfcb671588857507e924a8249.png)](http://dockone.io/uploads/article/20211128/3fb4b5ccfcb671588857507e924a8249.png)


无阻塞的接收读写：

[![14.png](http://dockone.io/uploads/article/20211128/642263a7109cd2b4f7ac12af41d3dfa5.png)](http://dockone.io/uploads/article/20211128/642263a7109cd2b4f7ac12af41d3dfa5.png)


再用一张流程图辅助解释下（系统实际采用文件句柄，此时用Socket来代替，方便大家理解）：

[![15.png](http://dockone.io/uploads/article/20211128/e28c49ad950b831f9a13a9a30efc39e3.png)](http://dockone.io/uploads/article/20211128/e28c49ad950b831f9a13a9a30efc39e3.png)


当然操作系统的多路复用有好几种实现方式，我们经常使用的select()，epoll模式这里不做过多的解释，有兴趣的可以查看相关文档，IO的发展后面还有异步、事件等模式

### NIO线程模型解释

我们一起来聊了阻塞、非阻塞、IO多路复用模式，那Redis采用的是哪种呢？

Redis采用的是IO多路复用模式，所以我们重点来了解下多路复用这种模式，如何在更好的落地到我们系统中，不可避免的我们要聊下Reactor模式。

首先我们做下相关的名词解释：

- ==**Reactor**：类似NIO编程中的Selector，负责I/O事件的派发；==
- **Acceptor**：NIO中接收到事件后，处理连接的那个分支逻辑；
- **Handler**：消息读写处理等操作类。



#### **单Reactor单线程模型**

[![16.png](http://dockone.io/uploads/article/20211128/32a40ec1aaf53ed93379e2a5e9f18e7d.png)](http://dockone.io/uploads/article/20211128/32a40ec1aaf53ed93379e2a5e9f18e7d.png)


处理流程：Reactor监听连接事件、Socket事件，当有连接事件过来时交给Acceptor处理，当有Socket事件过来时交个对应的Handler处理。

优点：

- 模型比较简单，所有的处理过程都在一个连接里；
- 实现上比较容易，模块功能也比较解耦，Reactor负责多路复用和事件分发处理，Acceptor负责连接事件处理，Handler负责Scoket读写事件处理。


缺点：

- 只有一个线程，**连接处理和业务处理共用一个线程**，无法充分利用CPU多核的优势。
- 在**流量不是特别大、业务处理比较快的时候**系统可以有很好的表现，当流量比较大、读写事件比较耗时情况下，容易导致系统出现性能瓶颈。


怎么去解决上述问题呢？**既然业务处理逻辑可能会影响系统瓶颈，那我们是不是可以把业务处理逻辑单拎出来，交给线程池来处理**，一方面减小对主线程的影响，另一方面利用CPU多核的优势。这一点希望大家要理解透彻，方便我们后续理解Redis由单线程模型到多线程模型的设计的思路。

#### 多Reactor多线程模型

[![18.png](http://dockone.io/uploads/article/20211128/6042d603dcb1e6cb55a8d9425d20505b.png)](http://dockone.io/uploads/article/20211128/6042d603dcb1e6cb55a8d9425d20505b.png)


**这种模型相对单Reactor多线程模型，只是将Scoket的读写处理从mainReactor中拎出来，交给subReactor线程来处理。**

处理流程：

- mainReactor主线程负责连接事件的监听和处理，当Acceptor处理完连接过程后，主线程将连接分配给subReactor；
- **subReactor负责mainReactor分配过来的Socket的监听和处理**，当有Socket事件过来时交个对应的Handler处理；
- Handler完成读事件后，包装成一个任务对象，交给线程池来处理，把业务处理逻辑交给其他线程来处理。


优点：

- 让主线程专注于连接事件的处理，子线程专注于读写事件吹，从设计上进一步解耦；
- 利用CPU多核的优势。


缺点：实现上会比较复杂，在极度追求单机性能的场景中可以考虑使用。

## 2.Redis的线程模型

#### 概述

以上我们聊了，IO网路模型的发展历史，也聊了IO多路复用的reactor模式。那Redis采用的是哪种reactor模式呢？在回答这个问题前，我们先梳理几个概念性的问题。

Redis服务器中有两类事件，文件事件和时间事件。

- **文件事件**：在这里可以把文件理解为Socket相关的事件，比如连接、读、写等；
- **时间时间**：可以理解为定时任务事件，比如一些定期的RDB持久化操作。


本文重点聊下Socket相关的事件。

#### 模型图

首先我们来看下Redis服务的线程模型图。

[![19.png](http://dockone.io/uploads/article/20211128/9e9adb183f51be8c67e9d7c053a1778f.png)](http://dockone.io/uploads/article/20211128/9e9adb183f51be8c67e9d7c053a1778f.png)


IO多路复用负责各事件的监听（连接、读、写等），当有事件发生时，将对应事件放入队列中，由事件分发器根据事件类型来进行分发；

如果是连接事件，则分发至连接应答处理器；GET、SET等redis命令分发至命令请求处理器。

命令处理完后产生命令回复事件，再由事件队列，到事件分发器，到命令回复处理器，回复客户端响应。

#### 一次客户端和服务端的交互流程

**连接流程**

[![20.png](http://dockone.io/uploads/article/20211128/6e29f650434627cecf33e45cab14ca8a.png)](http://dockone.io/uploads/article/20211128/6e29f650434627cecf33e45cab14ca8a.png)


连接过程

- Redis服务端主线程监听固定端口，并将连接事件绑定连接应答处理器。
- 客户端发起连接后，连接事件被触发，IO多路复用程序将连接事件包装好后丢人事件队列，然后由事件分发处理器分发给连接应答处理器。
- 连接应答处理器创建client对象以及Socket对象，我们这里关注Socket对象，并产生ae_readable事件，和命令处理器关联，标识后续该Socket对可读事件感兴趣，也就是开始接收客户端的命令操作。
- 当前过程都是由一个主线程负责处理。


**命令执行流程**

[![21.png](http://dockone.io/uploads/article/20211128/ce58f3e77b0995f7d2773e7ca20c153f.png)](http://dockone.io/uploads/article/20211128/ce58f3e77b0995f7d2773e7ca20c153f.png)


SET命令执行过程：

- 客户端发起SET命令，IO多路复用程序监听到该事件后（读事件），将数据包装成事件丢到事件队列中（事件在上个流程中绑定了命令请求处理器）；
- 事件分发处理器根据事件类型，将事件分发给对应的命令请求处理器；
- 命令请求处理器，读取Socket中的数据，执行命令，然后产生ae_writable事件，并绑定命令回复处理器；
- IO多路复用程序监听到写事件后，将数据包装成事件丢到事件队列中，事件分发处理器根据事件类型分发至命令回复处理器；
- 命令回复处理器，将数据写入Socket中返回给客户端。



#### 模型优缺点

以上流程分析我们可以看出Redis采用的是==单线程Reactor模型==，我们也分析了这种模式的优缺点，那Redis为什么还要采用这种模式呢？

Redis本身的特性：命令执行基于内存操作，业务处理逻辑比较快，所以命令处理这一块单线程来做也能维持一个很高的性能。

优点：Reactor单线程模型的优点，参考上文。

缺点：

- Reactor单线程模型的缺点也同样在Redis中来体现，==唯一不同的地方就在于业务逻辑处理（命令执行）这块不是系统瓶颈点==。
- ==随着流量的上涨==，IO操作的的耗时会越来越明显（read操作，内核中读数据到应用程序。write操作，应用程序中的数据到内核），当达到一定阀值时系统的瓶颈就体现出来了。


Redis又是如何去解的呢？

哈哈~将耗时的点从主线程拎出来呗？那Redis的新版本是这么做的吗？我们一起来看下。

#### Redis多线程模式

Redis 的多线程模式默认是关闭的，需要用户在 `redis.conf` 配置文件中开启：

```conf
io-threads 4
io-threads-do-reads yes
```



[![22.png](http://dockone.io/uploads/article/20211128/610ede68e9d331a9616610eda0c99892.png)](http://dockone.io/uploads/article/20211128/610ede68e9d331a9616610eda0c99892.png)

```

```


Redis的多线程模型跟”多Reactor多线程模型“、“单Reactor多线程模型有点区别”，但同时用了两种Reactor模型的思想，具体如下：

- ==Redis的多线程模型是将IO操作多线程化，本身逻辑处理过程（命令执行过程）依旧是单线程==，借助了单Reactor思想，实现上又有所区分。
- 将IO操作多线程化，又跟单Reactor衍生出多Reactor的思想一致，==都是将IO操作从主线程中拎出来==。


命令执行大致流程：

- 客户端发送请求命令，触发读就绪事件，服务端主线程将Socket（为了简化理解成本，统一用Socket来代表连接）放入一个队列，主线程不负责读；
- IO 线程通过Socket读取客户端的请求命令，主线程忙轮询，等待所有 I/O 线程完成读取任务，IO线程只负责读不负责执行命令；
- 主线程一次性执行所有命令，执行过程和单线程一样，然后需要返回的连接放入另外一个队列中，有IO线程来负责写出（主线程也会写）；
- 主线程忙轮询，等待所有 I/O 线程完成写出任务。



#### 多线程模式详解

> Redis 6.0 之前单线程指的是 Redis 只有一个线程干活么？

非也，**Redis 在处理客户端的请求时，包括获取 (socket 读)、解析、执行、内容返回 (socket 写) 等都由一个顺序串行的主线程处理，这就是所谓的「单线程」**。

其中执行命令阶段，由于 Redis 是单线程来处理命令的，所有每一条到达服务端的命令不会立刻执行，所有的命令都会进入一个 Socket 队列中，当 socket 可读则交给单线程事件分发器逐个被执行。

![img](https://segmentfault.com/img/remote/1460000040376113)

此外，有些命令操作可以==用后台线程或子进程执行（比如数据删除、快照生成、AOF 重写）==。

> 码老湿，那 Redis 6.0 为啥要引入多线程呀？

随着硬件性能提升，==Redis 的性能瓶颈可能出现网络 IO 的读写==，也就是：**单个线程处理网络读写的速度跟不上底层网络硬件的速度**。

读写网络的 `read/write` 系统调用占用了Redis 执行期间大部分CPU 时间，瓶颈主要在于网络的 IO 消耗, 优化主要有两个方向:

- 提高网络 IO 性能，典型的实现比如使用 `DPDK `来替代内核网络栈的方式。
- 使用多线程充分利用多核，提高网络请求读写的并行度，典型的实现比如 `Memcached`。

添加对用户态网络协议栈的支持，需要修改 Redis 源码中和网络相关的部分（例如修改所有的网络收发请求函数），这会带来很多开发工作量。

而且新增代码还可能引入新 Bug，导致系统不稳定。

所以，Redis 采用多个 IO 线程来处理网络请求，提高网络请求处理的并行度。

**需要注意的是，Redis 多 IO 线程模型只用来处理网络读写请求，对于 Redis 的读写命令，依然是单线程处理**。

这是因为，网络处理经常是瓶颈，通过多线程并行处理可提高性能。

而继续使用单线程执行读写命令，不需要为了保证 Lua 脚本、事务、等开发多线程安全机制，实现更简单。

**架构图如下**：

![图片来源：后端研究所](https://segmentfault.com/img/remote/1460000040376114)

> 主线程与 IO 多线程是如何实现协作呢？

如下图：

![Redis多线程与IO线程](https://segmentfault.com/img/remote/1460000040376115)

**主要流程**：

1. 主线程负责接收建立连接请求，获取 `socket` 放入全局等待读处理队列；
2. 主线程通过轮询将可读 `socket` 分配给 IO 线程；
3. 主线程阻塞等待 IO 线程读取 `socket` 完成；
4. 主线程执行 IO 线程读取和解析出来的 Redis 请求命令；
5. 主线程阻塞等待 IO 线程将指令执行结果回写回 `socket`完毕；
6. 主线程清空全局队列，等待客户端后续的请求。

思路：**将主线程 IO 读写任务拆分出来给一组独立的线程处理，使得多个 socket 读写可以并行化，但是 Redis 命令还是主线程串行执行。**