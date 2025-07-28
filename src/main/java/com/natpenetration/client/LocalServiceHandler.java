package com.natpenetration.client;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地服务处理器
 * 负责连接本地服务并处理数据转发
 */
public class LocalServiceHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(LocalServiceHandler.class);
    
    private final NatClient client;
    private final ExecutorService executorService;
    private final AtomicBoolean running;
    
    private Socket localServiceSocket;
    private InputStream localInputStream;
    private OutputStream localOutputStream;
    
    public LocalServiceHandler(NatClient client) {
        this.client = client;
        // 使用虚拟线程执行器
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.running = new AtomicBoolean(false);
    }
    
    /**
     * 启动本地服务处理器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("启动本地服务处理器");
            
            // 连接本地服务
            connectToLocalService();
            
            // 启动数据读取线程
            startDataReader();
            
        } else {
            logger.warn("本地服务处理器已经在运行中");
        }
    }
    
    /**
     * 连接本地服务
     */
    private void connectToLocalService() {
        try {
            logger.info("连接本地服务: {}:{}", client.getLocalHost(), client.getLocalPort());
            
            localServiceSocket = new Socket(client.getLocalHost(), client.getLocalPort());
            localServiceSocket.setSoTimeout(Config.CONNECTION_TIMEOUT);
            
            localInputStream = localServiceSocket.getInputStream();
            localOutputStream = localServiceSocket.getOutputStream();
            
            logger.info("成功连接到本地服务");
            
        } catch (IOException e) {
            logger.error("连接本地服务失败", e);
            stop();
        }
    }
    
    /**
     * 启动数据读取线程
     */
    private void startDataReader() {
        executorService.submit(() -> {
            try {
                byte[] buffer = new byte[Config.BUFFER_SIZE];
                int bytesRead;
                
                while (running.get() && (bytesRead = localInputStream.read(buffer)) != -1) {
                    // 将读取的数据发送给服务端
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    
                    // 通过隧道发送数据
                    client.getTunnelHandler().sendData(data);
                    
                    if (Config.DEBUG_MODE) {
                        logger.debug("从本地服务读取数据并发送给服务端，数据长度: {}", bytesRead);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("读取本地服务数据时发生错误", e);
                }
            } finally {
                stop();
            }
        });
    }
    
    /**
     * 转发数据到本地服务
     */
    public void forwardToLocalService(byte[] data) {
        if (!running.get() || localOutputStream == null) {
            logger.warn("本地服务处理器未运行或本地服务未连接");
            return;
        }
        
        try {
            localOutputStream.write(data);
            localOutputStream.flush();
            
            if (Config.DEBUG_MODE) {
                logger.debug("数据已转发到本地服务，数据长度: {}", data.length);
            }
        } catch (IOException e) {
            logger.error("转发数据到本地服务失败", e);
            stop();
        }
    }
    
    /**
     * 停止本地服务处理器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("正在停止本地服务处理器...");
            
            // 关闭本地服务套接字
            try {
                if (localServiceSocket != null && !localServiceSocket.isClosed()) {
                    localServiceSocket.close();
                }
            } catch (IOException e) {
                logger.error("关闭本地服务套接字时发生错误", e);
            }
            
            // 关闭线程池
            executorService.shutdown();
            
            logger.info("本地服务处理器已停止");
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * 检查本地服务是否连接
     */
    public boolean isConnected() {
        return localServiceSocket != null && !localServiceSocket.isClosed();
    }
} 