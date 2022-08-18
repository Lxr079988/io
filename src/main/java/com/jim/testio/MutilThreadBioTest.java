package com.jim.testio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * @Classname MutilThreadBioTest
 * @Description TODO
 * @Date 2022/8/18 14:59
 * @Created by Jimliu
 */
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
