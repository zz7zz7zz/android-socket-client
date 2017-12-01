package com.open.net.client.impl.nio;

import com.open.net.client.impl.nio.processor.SocketProcessor;
import com.open.net.client.structures.IConnectListener;
import com.open.net.client.structures.TcpAddress;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;

/**
 * author       :   long
 * created on   :   2017/11/30
 * description  :   连接器
 */

public final class NioConnector {

    private final int STATE_CLOSE			= 1<<1;//socket关闭
    private final int STATE_CONNECT_START	= 1<<2;//开始连接server
    private final int STATE_CONNECT_SUCCESS	= 1<<3;//连接成功
    private final int STATE_CONNECT_FAILED	= 1<<4;//连接失败

    private NioClient mClient;
    private TcpAddress[] tcpArray   = null;
    private int          index      = -1;

    private int state = STATE_CLOSE;
    private SocketProcessor mSocketProcessor;
    private IConnectListener mIConnectListener;
    private HashMap<Long,SocketProcessor> connects = new HashMap<>();
    private long connect_token;

    private INioConnectListener mProxyConnectStatusListener = new INioConnectListener() {
        @Override
        public synchronized void onConnectSuccess(long connect_token , SocketChannel socketChannel, Selector mSelector) throws IOException {
            if(connect_token != NioConnector.this.connect_token){//两个请求都不是同一个，说明是之前连接了，现在重连了
                SocketProcessor dropProcessor = connects.get(connect_token);
                if(null != dropProcessor){
                    dropProcessor.close();
                    connects.remove(connect_token);
                }
                return;
            }

            mClient.init(socketChannel,mSelector);
            state = STATE_CONNECT_SUCCESS;

            if(null != mIConnectListener ){
                mIConnectListener.onConnectionSuccess();
            }
        }

        @Override
        public synchronized void onConnectFailed(long connect_token) {
            if(connect_token != NioConnector.this.connect_token){//两个请求都不是同一个，说明是之前连接了，现在重连了
                SocketProcessor dropProcessor = connects.get(connect_token);
                if(null != dropProcessor){
                    dropProcessor.close();
                    connects.remove(connect_token);
                }
                return;
            }

            state = STATE_CONNECT_FAILED;
            connect();//try to connect next ip port

            if(null !=mIConnectListener ){
                mIConnectListener.onConnectionFailed();
            }
        }
    };

    public NioConnector(NioClient mClient, TcpAddress[] tcpArray, IConnectListener mConnectListener) {
        this.mClient = mClient;
        this.tcpArray = tcpArray;
        this.mIConnectListener = mConnectListener;
    }

    public void setConnectAddress(TcpAddress[] tcpArray ){
        this.tcpArray = tcpArray;
    }

    //-------------------------------------------------------------------------------------------
    public boolean isConnected(){
        return state == STATE_CONNECT_SUCCESS;
    }

    public boolean isConnecting(){
        return state == STATE_CONNECT_START;
    }

    public boolean isClosed(){
        return state == STATE_CLOSE;
    }

    //-------------------------------------------------------------------------------------------
    public synchronized void connect() {
        startConnect();
    }

    public synchronized void reconnect(){
        stopConnect();
        //reset the ip/port index of tcpArray
        if(index+1 >= tcpArray.length || index+1 < 0){
            index = -1;
        }
        startConnect();
    }

    public synchronized void disconnect(){
        stopConnect();
    }

    //-------------------------------------------------------------------------------------------
    public void checkConnect() {
        //1.没有连接,需要进行重连
        //2.在连接不成功，并且也不在重连中时，需要进行重连;
        if(null == mSocketProcessor){
            startConnect();
        }else if(!isConnected() && !isConnecting()){
            startConnect();
        }else{
            if(isConnected()){
                mSocketProcessor.wakeUp();
            }else{
                //说明正在重连中
            }
        }
    }

    private void startConnect() {
        //非关闭状态(连接成功，或者正在重连中)
        if(!isClosed()){
            return;
        }

        index++;
        if(index < tcpArray.length && index >= 0){
            state = STATE_CONNECT_START;
            connect_token = System.currentTimeMillis();
            mSocketProcessor = new SocketProcessor(connect_token,mClient,tcpArray[index].ip,tcpArray[index].port, mProxyConnectStatusListener);
            connects.put(connect_token,mSocketProcessor);
            mSocketProcessor.start();
        }else{
            index = -1;

            //循环连接了一遍还没有连接上，说明网络连接不成功，此时清空消息队列，防止队列堆积
            mClient.clearUnreachableMessages();
        }
    }

    private void stopConnect() {
        connect_token = -1;
        state = STATE_CLOSE;
        mClient.onClose();

        if(null != mSocketProcessor) {
            mSocketProcessor.close();
        }
        mSocketProcessor = null;
    }

}
