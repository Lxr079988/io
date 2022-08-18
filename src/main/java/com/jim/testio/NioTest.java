package com.jim.testio;

/**
 * @Classname NioTest
 * @Description TODO
 * @Date 2022/8/18 15:21
 * @Created by Jimliu
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;


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