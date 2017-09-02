package com.scdevteam.proxies.cr;

import com.scdevteam.Utils;
import com.scdevteam.WriterUtils;
import com.scdevteam.crypto.sodium.crypto.ClientCrypto;
import com.scdevteam.proto.MessageMap;
import com.scdevteam.messages.RequestMessage;
import com.scdevteam.messages.ResponseMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ClashRoyaleClient implements Runnable {

    private OutputStream mOut;

    private final ClashRoyaleProxy mProxy;
    private final ClientCrypto mSodium;

    private final Object mLocker = new Object();

    public ClashRoyaleClient(ClashRoyaleProxy proxy) {
        mProxy = proxy;
        mSodium = new ClientCrypto(Utils.hexToBuffer(
                "ac30dcbea27e213407519bc05be8e9d930e63f873858479946c144895fa3a26b"),
                mProxy.getCrypto());
        new Thread(this).start();
    }

    @Override
    public void run() {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("game.clashroyaleapp.com", 9339));

            InputStream inputStream = socket.getInputStream();
            mOut = socket.getOutputStream();

            synchronized (mLocker) {
                mLocker.notifyAll();
            }

            while (socket.isConnected()) {
                byte[] headers = new byte[7];

                if (inputStream.read(headers, 0, 7) > 0) {
                    int msgId = Utils.toInt16(Arrays.copyOfRange(headers, 0, 2));
                    int len = Utils.toInt24(Arrays.copyOfRange(headers, 2, 5));
                    int ver = Utils.toInt16(Arrays.copyOfRange(headers, 5, 7));

                    ResponseMessage responseMessage = new ResponseMessage(msgId, len, ver);

                    ByteBuffer payload = ByteBuffer.allocate(len);

                    int o = len;
                    while (o != 0) {
                        byte[] a = new byte[o];

                        int r;
                        if ((r = inputStream.read(a, 0, o)) == -1) {
                            break;
                        }
                        o -= r;

                        payload.put(ByteBuffer.wrap(a, 0, r));
                    }

                    responseMessage.finish(payload, mSodium);

                    WriterUtils.postDanger("[SERVER] " +
                            MessageMap.getMessageType(responseMessage.getMessageID()) +
                            " (" + responseMessage.getMessageID() + ")");

                    String map = MessageMap.getMap(mProxy.getMapper(),
                            responseMessage.getMessageID(),
                            responseMessage.getDecryptedPayload());

                    WriterUtils.post(Utils.toHexString(responseMessage.getDecryptedPayload()));

                    if (map != null) {
                        WriterUtils.post(map);
                    }
                    WriterUtils.post("");

                    mProxy.sendMessageToClient(responseMessage.getMessageID(),
                            responseMessage.getVersion(), responseMessage.getDecryptedPayload());
                }
            }

            WriterUtils.postError("Game server disconnected");
        } catch (IOException ignored) {
            WriterUtils.postError("Game server disconnected");
        }
    }

    public void sendMessageToServer(final int messageId, final int version, final byte[] payload) {
        final RequestMessage requestMessage =
                new RequestMessage(messageId, payload.length, version, payload, mSodium);

        synchronized (mLocker) {
            while (mOut == null) {
                try {
                    mLocker.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        try {
            mOut.write(requestMessage.buildMessage().array());
            mOut.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ClientCrypto getCrypto() {
        return mSodium;
    }
}
