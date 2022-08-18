package com.jim.testio;

/**
 * @Classname 多路复用
 * @Description TODO
 * @Date 2022/8/18 15:43
 * @Created by Jimliu
 */

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Set;

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