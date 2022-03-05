package io.github.vertxchina;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Leibniz on 2022/02/25 10:27 PM
 */
@ExtendWith(VertxExtension.class)
public class TcpServerVerticleTest {

  @Test
  void singleClientSendMessageTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int port = 9527;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .compose(did -> createClients(vertx, port, 1))
      .compose(cf -> sendMessages(vertx, cf, 1).get(0))
      .onSuccess(client -> {
        assert client.receivedMsgList.size() == 0; //登陆后响应消息不包含有message域，不统计在内，自身发出的消息不再发回来
        testCtx.completeNow();
      })
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void multiClientSendMessageMutuallyTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int port = 6666;
    int clientNum = 3;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .compose(did -> createClients(vertx, port, clientNum))
      .compose(cf -> CompositeFuture.all(cast(sendMessages(vertx, cf, 1))))
      .onSuccess(closed -> {
        for (Object o : closed.result().list()) {
          assert ((TreeNewBeeClient) o).receivedMsgList.size() == clientNum - 1; //N条其他Clients发出的消息，仅保留有消息（message字段）的消息，登陆后的响应和退出消息不保留，另外自身发送的消息不再返回
        }
        testCtx.completeNow();
      })
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }

  @Test
  void singleClientSendMessageErrorTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int port = 9527;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .compose(did -> createClients(vertx, port, 1))
      .compose(ar -> sendErrorMessages(vertx, ar).get(0))
      .onSuccess(msgList -> {
        assert msgList.size() == 1; //发送错误消息应返回解析错误消息
        var stacktrace = msgList.get(0).getString("message");
        System.out.println(stacktrace);
        testCtx.completeNow();
      })
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }

  @Test
  void chatLogSendTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int port = 9527;
    int prevClientSendMsgNum = 10;
    int chatLogSize = 5;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port).put("TcpServerVerticle.chatLogSize", chatLogSize);
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
      .compose(did -> createClients(vertx, port, 1))
      .compose(cf -> sendMessages(vertx, cf, prevClientSendMsgNum).get(0))
      .compose(client -> {
        System.out.println("First client send " + client.sendMsgList.size() + " message(s), received " + client.receivedMsgList.size() + " messages");
        assert client.sendMsgList.size() == prevClientSendMsgNum;
        assert client.receivedMsgList.size() == 0;
        return createClients(vertx, port, 1);
      })
      .compose(cf -> sendMessages(vertx, cf, 1).get(0))
      .onSuccess(client -> {
        System.out.println("Second client send " + client.sendMsgList.size() + " message(s), received " + client.receivedMsgList.size() + " messages");
        assert client.receivedMsgList.size() == Math.min(prevClientSendMsgNum, chatLogSize); //服务器只保留最近chatLogSize条记录
        testCtx.completeNow();
      })
      .onFailure(testCtx::failNow);

    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }


  @Test
  void fsMessageSaveTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int chatLogSize = 5;
    FSMessageBuffer fsMessageBuffer = new FSMessageBuffer(vertx, chatLogSize);
    for (int i = 0; i < 5; i++) {
      fsMessageBuffer.add(new JsonObject().put("message", i));
    }
    fsMessageBuffer.fromPersisted();
    assert fsMessageBuffer.storedMessages().size() == chatLogSize;
    for (int i = 0; i < fsMessageBuffer.storedMessages().size(); i++) {
      assert fsMessageBuffer.storedMessages().get(i).getInteger("message") == i ;
    }
    vertx.fileSystem().deleteBlocking(FSMessageBuffer.TEMP_STORE_PATH);
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }

  @Test
  void chatFsMessageSaveTest(Vertx vertx, VertxTestContext testCtx) throws Throwable {
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() Start");
    int port = 7654;
    int prevClientSendMsgNum = 10;
    int chatLogSize = 5;
    JsonObject config = new JsonObject().put("TcpServerVerticle.port", port).put("TcpServerVerticle.chatLogSize", chatLogSize);
    try {
      vertx.fileSystem().deleteBlocking(FSMessageBuffer.TEMP_STORE_PATH);
    } catch (Exception ignore) {

    }
    vertx.deployVerticle(TcpServerVerticle.class, new DeploymentOptions().setConfig(config))
            .compose(did -> createClients(vertx, port, 1))
            .compose(cf -> sendMessages(vertx, cf, prevClientSendMsgNum).get(0))
            .onSuccess(client -> {
              FSMessageBuffer fsMessageBuffer = new FSMessageBuffer(vertx, chatLogSize);
              vertx.setTimer(2000, t -> {
                System.err.println(fsMessageBuffer.storedMessages());
                assert fsMessageBuffer.storedMessages().size() == 5;
              });
            })
            .onFailure(testCtx::failNow);
    assert testCtx.awaitCompletion(10, TimeUnit.SECONDS);
    if (testCtx.failed()) {
      throw testCtx.causeOfFailure();
    }
    System.out.println("====> " + Thread.currentThread().getStackTrace()[1].getMethodName() + "() End");
  }


  private List<Future<List<JsonObject>>> sendErrorMessages(Vertx vertx, CompositeFuture ar) {
    List<Future<List<JsonObject>>> closeFutures = new ArrayList<>();
    for (Object o : ar.list()) {
      if (o instanceof TreeNewBeeClient client) {
        var socket = client.socket;
        socket.write("fjdslkjlfa\r\n");
        Promise<List<JsonObject>> promise = Promise.promise();
        closeFutures.add(promise.future());
        socket.closeHandler(v -> {
          System.out.println("Client " + socket + " closed");
          promise.complete(client.receivedMsgList);
        });
        vertx.setTimer(1000L, tid -> socket.close());
      }
    }
    return closeFutures;
  }

  private List<Future<TreeNewBeeClient>> sendMessages(Vertx vertx, CompositeFuture ar, int msgNum) {
    List<Future<TreeNewBeeClient>> closeFutures = new ArrayList<>();
    for (Object o : ar.list()) {
      if (o instanceof TreeNewBeeClient client) {
        var clientId = client.id;
        var socket = client.socket;
        for (int i = 0; i < msgNum; i++) {
          client.sendMsg("Hello gays! I'm client " + clientId);
        }
        Promise<TreeNewBeeClient> promise = Promise.promise();
        closeFutures.add(promise.future());
        socket.closeHandler(v -> {
          System.out.println("Client " + socket + " closed");
          promise.complete(client);
        });
        vertx.setTimer(4000L, tid -> socket.close());
      }
    }
    return closeFutures;
  }

  @SuppressWarnings("rawtypes")
  private CompositeFuture createClients(Vertx vertx, int port, int num) {
    List<Future> createClientFutures = new ArrayList<>();
    for (int i = 0; i < num; i++) {
      var clientId = i;
      createClientFutures.add(
        vertx.createNetClient()
          .connect(port, "localhost")
          .map(s -> new TreeNewBeeClient(s, clientId, new ArrayList<>(), new ArrayList<>()))
          .onSuccess(client -> {
            System.out.println("Client " + clientId + " Connected!");
            client.socket.handler(client::receiveMsg);
          })
          .onFailure(e -> System.out.println("Failed to connect: " + e.getMessage()))
      );
    }
    return CompositeFuture.all(createClientFutures);
  }

  record TreeNewBeeClient(NetSocket socket, int id, List<JsonObject> receivedMsgList, List<String> sendMsgList) {
    void receiveMsg(Buffer buffer) {
      try {
        String[] jsonStrings = buffer.toString().split("\r\n");
        for (String jsonString : jsonStrings) {
          JsonObject msg = new JsonObject(jsonString.trim());
          System.out.println("Client " + id + " Received message: " + msg);
          if (msg.containsKey("message")) {
            receivedMsgList.add(msg);
          }
        }
      } catch (Exception e) {
        System.out.println("Client " + id + " parse message err: " + e.getMessage() + "original message:" + buffer.toString());
      }
    }

    void sendMsg(String msg) {
      socket.write(new JsonObject()
        .put("time", System.currentTimeMillis())
        .put("message", msg)
        .put("fromClientId", id).toString() + "\r\n");
      sendMsgList.add(msg);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> T cast(Object obj) {
    return (T) obj;
  }
}