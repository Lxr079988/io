package com.jim.testio;

/**
 * @Classname BIOTest
 * @Description TODO
 * @Date 2022/8/18 14:30
 * @Created by Jimliu
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

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