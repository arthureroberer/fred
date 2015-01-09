package freenet.clients.fcp;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import freenet.clients.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/**
 * An FCP connection between:<br>
 * - a fred plugin which provides its services by a FCP server.<br>
 * - a client application which uses those services. The client may be a plugin as well, or be
 *   connected by a networked FCP connection.<br><br>
 * 
 * <h1>How to use this properly</h1><br>
 * 
 * You can read the following JavaDoc for a nice overview of how to use this properly from the
 * perspective of your server or client implementation:<br>
 * - {@link PluginRespirator#connectToOtherPlugin(String, ClientSideFCPMessageHandler)}<br>
 * - {@link PluginRespirator#getFCPPluginClientByID(UUID)}<br>
 * - {@link FredPluginFCPMessageHandler}<br>
 * - {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler}<br>
 * - {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}<br>
 * - {@link FCPPluginMessage}<br><br>
 * 
 * <h1>Debugging</h1><br>
 * 
 * You can configure the {@link Logger} to log "freenet.clients.fcp.FCPPluginConnection:DEBUG" to
 * cause logging of all sent and received messages.<br>
 * This is usually done on the Freenet web interface at Configuration / Logs / Detailed priority 
 * thresholds.<br>
 * ATTENTION: The log entries will appear at the time when the messages were queued for sending, not
 * when they were delivered. Delivery usually happens in a separate thread. Thus, the relative order
 * of arrival of messages can be different to the order of their appearance in the log file.<br>
 * If you need to know the order of arrival, add logging to your message handler. Also don't forget
 * that {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)} will not deliver replies
 * to the message handler but only return them instead.<br><br>
 * 
 * <h1>Internals</h1><br>
 * 
 * This section is not interesting to server or client implementations. You might want to read it
 * if you plan to work on the fred-side implementation of FCP plugin messaging.
 * 
 * <h2>Code path of sending messages</h2>
 * <p>There are two possible code paths for client connections, depending upon the location of the
 * client. The server is always running inside the node.<br><br>
 * 
 * NOTICE: These two code paths only apply to asynchronous, non-blocking messages. For blocking,
 * synchronous messages sent by {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)},
 * there is an overview at {@link #synchronousSends}. The overview was left out here because they
 * are built on top of regular messages, so the code paths mentioned here mostly apply.<br><br>
 * 
 * The two possible paths are:<br/>
 * <p>1. The server is running in the node, the client is not - also called networked FCP
 * connections:<br/>
 * - The client connects to the node via network and sends FCP message of type
 *   <a href="https://wiki.freenetproject.org/FCPv2/FCPPluginMessage">FCPPluginMessage</a> (which
 *   will be internally represented by class {@link FCPPluginClientMessage}).<br/>
 * - The {@link FCPServer} creates a {@link FCPConnectionHandler} whose
 *   {@link FCPConnectionInputHandler} receives the FCP message.<br/>
 * - The {@link FCPConnectionInputHandler} uses {@link FCPMessage#create(String, SimpleFieldSet)}
 *   to parse the message and obtain the actual {@link FCPPluginClientMessage}.<br/>
 * - The {@link FCPPluginClientMessage} uses {@link FCPConnectionHandler#getPluginClient(String)} to
 *   obtain the FCPPluginClient which wants to send.<br/>
 * - The {@link FCPPluginClientMessage} uses {@link FCPPluginClient#send(SendDirection,
 *   FCPPluginMessage)} to send the message to the server plugin.<br/>
 * - The FCP server plugin handles the message at
 *   {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}.
 *   <br/>
 * - As each FCPPluginClient object exists for the lifetime of a network connection, the FCP server
 *   plugin may store the UUID of the FCPPluginClient and query it via
 *   {@link PluginRespirator#getFCPPluginClientByID(UUID)}. It can use this to send messages to the
 *   client application on its own, that is not triggered by any client messages.<br/>
 * </p>
 * <p>2. The server and the client are running in the same node, also called intra-node FCP
 * connections:</br>
 * - The client plugin uses {@link PluginRespirator#connectToOtherPlugin(String,
 *   FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} to try to create a connection.<br/>
 * - The {@link PluginRespirator} uses {@link FCPServer#createFCPPluginClientForIntraNodeFCP(String,
 *   FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} to get a FCPPluginClient.<br/>
 * - The client plugin uses the send functions of the FCPPluginClient. Those are the same as with
 *   networked FCP connections.<br/>
 * - The FCP server plugin handles the message at
 *   {@link ServerSideFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}.
 *   That is the same handler as with networked FCP connections.<br/>
 * - The client plugin keeps a strong reference to the FCPPluginClient in memory as long as it wants
 *   to keep the connection open.<br/>
 * - Same as with networked FCP connections, the FCP server plugin can store the UUID of the
 *   FCPPluginClient and in the future re-obtain the client by
 *   {@link PluginRespirator#getFCPPluginClientByID(UUID)}. It can use this to send messages to the
 *   client application on its own, that is not triggered by any client messages. <br/>
 * - Once the client plugin is done with the connection, it discards the strong reference to the
 *   FCPPluginClient. Because the {@link FCPPluginClientTracker} monitors garbage collection of
 *   {@link FCPPluginClient} objects, getting rid of all strong references to a
 *   {@link FCPPluginClient} is sufficient as a disconnection mechanism.<br/>
 *   Thus, an intra-node client connection is considered as disconnected once the FCPPluginClient is
 *   not strongly referenced by the client plugin anymore. If a server plugin then tries to obtain
 *   the client by its UUID again (via the aforementioned
 *   {@link PluginRespirator#getFCPPluginClientByID(UUID)}, the get will fail. So if the server
 *   plugin stores client UUIDs, it needs no special disconnection mechanism except for periodically
 *   trying to send a message to each client. Once obtaining the client by its UUID fails, or
 *   sending the message fails, the server can opportunistically purge the UUID from its database.
 *   <br/>This mechanism also works for networked FCP.<br>
 * </p></p>
 * 
 * <h2>Object lifecycle</h2>
 * <p>For each {@link #serverPluginName}, a single {@link FCPConnectionHandler} can only have a
 * single FCPPluginClient with the plugin of that name as connection partner. This is enforced by
 * {@link FCPConnectionHandler#getPluginClient(String)}. In other words: One
 * {@link FCPConnectionHandler} can only have one connection to a certain plugin.<br/>
 * The reason for this is the following: Certain plugins might need to store the UUID of a client in
 * their database so they are able to send data to the client if an event of interest to the client
 * happens in the future. Therefore, the UUID of a client must not change during the lifetime of
 * the connection. To ensure a permanent UUID of a client, only a single {@link FCPPluginClient} can
 * exist per server plugin per {@link FCPConnectionHandler}.<br>
 * If you  nevertheless need multiple clients to a plugin, you have to create multiple FCP
 * connections.<br/></p>
 * 
 * <p>
 * In opposite to {@link PersistentRequestClient}, a FCPPluginClient is kept in existence by fred
 * only while the actual client is connected (= in case of networked FCP the parent
 * {@link FCPConnectionHandler} exists; or in case of non-networked FCP while the FCPPluginClient is
 * strong-referenced by the client plugin).<br>
 * There is no such thing as persistence beyond client disconnection.<br/>
 * This was decided to simplify implementation:<br/>
 * - Persistence should be implemented by using the existing persistence framework of
 *   {@link PersistentRequestClient}. That would require extending the class though, and it is a
 *   complex class. The work for extending it was out of scope of the time limit for implementing
 *   this class.<br/>
 * - FCPPluginClient instances need to be created without a network connection for intra-node plugin
 *   connections. If we extended class {@link PersistentRequestClient}, a lot of care would have to
 *   be taken to allow it to exist without a network connection - that would even be more work.<br/>
 * </p>
 * 
 * @author xor (xor@freenetproject.org)
 */
public interface FCPPluginConnection {

    /**
     * @return A unique identifier among all FCPPluginClients.
     * @see The ID can be used with {@link PluginRespirator#getFCPPluginClientByID(UUID)}.
     */
    public UUID getID();

    /**
     * Can be used by both server and client implementations to send messages to each other.<br>
     * The messages sent by this function will be delivered to the remote side at either:
     * - the message handler {@link FredPluginFCPMessageHandler#
     *   handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}.<br>
     * - or, if existing, a thread waiting for a reply message in
     *   {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)}.<br><br>
     * 
     * This is an <b>asynchronous</b>, non-blocking send function.<br>
     * This has the following differences to the blocking send {@link #sendSynchronous(
     * SendDirection, FCPPluginMessage, long)}:<br>
     * - It may return <b>before</b> the message has been sent.<br>
     *   The message sending happens in another thread so this function can return immediately.<br>
     *   In opposite to that, a synchronousSend() would wait for a reply to arrive, so once it
     *   returns, the message is guaranteed to have been sent.<br>
     * - The reply is delivered to your message handler {@link FredPluginFCPMessageHandler}. It will
     *   not be directly available to the thread which called this function.<br>
     *   A synchronousSend() would return the reply to the caller.<br>
     * - You have no guarantee whatsoever that the message will be delivered.<br>
     *   A synchronousSend() will tell you that a reply was received, which guarantees that the
     *   message was delivered.<br>
     * - The order of arrival of messages is random.<br>
     *   A synchronousSend() only returns after the message was delivered already, so by calling
     *   it multiple times in a row on the same thread, you would enforce the order of the
     *   messages arriving at the remote side.<br><br>
     * 
     * ATTENTION: The consequences of this are:<br>
     * - Even if the function returned without throwing an {@link IOException} you nevertheless must
     *   <b>not</b> assume that the message has been sent.<br>
     * - If the function did throw an {@link IOException}, you <b>must</b> assume that the
     *   connection is dead and the message has not been sent.<br>
     *   You <b>must</b> consider this FCPPluginClient as dead then and create a fresh one.<br>
     * - You can only be sure that a message has been delivered if your message handler receives
     *   a reply message with the same value of
     *   {@link FCPPluginMessage#identifier} as the original message.<br>
     * - You <b>can</b> send many messages in parallel by calling this many times in a row.<br>
     *   But you <b>must not</b> call this too often in a row to prevent excessive threads creation.
     *   <br><br>
     * 
     * ATTENTION: If you plan to use this inside of message handling functions of your
     * implementations of the interfaces
     * {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler} or
     * {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}, be sure to read the JavaDoc
     * of the message handling functions first as it puts additional constraints on the usage
     * of the FCPPluginClient they receive.
     * 
     * @param direction
     *            Whether to send the message to the server or the client message handler.<br><br>
     * 
     *            While you <b>can</b> use this to send messages to yourself, be careful not to
     *            cause thread deadlocks with this. The function will call your message
     *            handler function of {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(
     *            FCPPluginClient, FCPPluginMessage)} in <b>a different thread</b>, so it should not
     *            cause deadlocks on its own, but you might produce deadlocks with your own thread
     *            synchronization measures.<br><br>
     * 
     * @param message
     *            You <b>must not</b> send the same message twice: This can break
     *            {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)}.<br>
     *            To ensure this, always construct a fresh FCPPluginMessage object when re-sending
     *            a message. If you use the constructor which allows specifying your own identifier,
     *            always generate a fresh, random identifier.<br>
     *            TODO: Code quality: Add a flag to FCPPluginMessage which marks the message as
     *            sent and use it to log an error if someone tries to send the same message twice.
     *            <br><br>
     * 
     * @throws IOException
     *             If the connection has been closed meanwhile.<br/>
     *             This FCPPluginClient <b>should be</b> considered as dead once this happens, you
     *             should then discard it and obtain a fresh one.
     * 
     *             <p><b>ATTENTION:</b> If this is not thrown, that does NOT mean that the
     *             connection is alive. Messages are sent asynchronously, so it can happen that a
     *             closed connection is not detected before this function returns.<br/>
     *             The only way of knowing that a send succeeded is by receiving a reply message
     *             in your {@link FredPluginFCPMessageHandler}.<br>
     *             If you need to know whether the send succeeded on the same thread which shall
     *             call the send function, you can also use {@link #sendSynchronous(SendDirection,
     *             FCPPluginMessage, long)} which will return the reply right away.</p>
     * @see #sendSynchronous(SendDirection, FCPPluginMessage, long)
     *          You may instead use the blocking sendSynchronous() if your thread needs to know
     *          whether messages arrived, to ensure a certain order of arrival, or to know
     *          the reply to a message.
     */
    public void send(SendDirection direction, FCPPluginMessage message) throws IOException;

    /**
     * Can be used by both server and client implementations to send messages in a blocking
     * manner to each other.<br>
     * The messages sent by this function will be delivered to the message handler
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginClient, FCPPluginMessage)}
     * of the remote side.<br><br>
     * 
     * This has the following differences to a regular non-synchronous
     * {@link #send(SendDirection, FCPPluginMessage)}:<br>
     * - It will <b>wait</b> for a reply message of the remote side before returning.<br>
     *   A regular send() would instead queue the message for sending, and then return immediately.
     * - The reply message will be <b>returned to the calling thread</b> instead of being passed to
     *   the message handler {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(
     *   FCPPluginClient, FCPPluginMessage)} in another thread.<br>
     *   NOTICE: It is possible that the reply message <b>is</b> passed to the message handler
     *   upon certain error conditions, for example if the timeout you specify when calling this
     *   function expires before the reply arrives. This is not guaranteed though.<br>
     * - Once this function returns without throwing, it is <b>guaranteed</b> that the message has
     *   arrived at the remote side.<br>
     * - The <b>order</b> of messages can be preserved: If you call sendSynchronous() twice in a
     *   row, the second call cannot execute before the first one has returned, and the returning
     *   of the first call guarantees that the first message was delivered already.<br>
     *   Regular send() calls deploy each message in a thread. This means that the order of delivery
     *   can be different than the order of sending.<br><br>
     * 
     * ATTENTION: This function can cause the current thread to block for a long time, while
     * bypassing the thread limit. Therefore, only use this if the desired operation at the remote
     * side is expected to execute quickly and the thread which sends the message <b>immediately</b>
     * needs one of these after sending it to continue its computations:<br>
     * - An guarantee that the message arrived at the remote side.<br>
     * - An indication of whether the operation requested by the message succeeded.<br>
     * - The reply to the message.<br>
     * - A guaranteed order of arrival of messages at the remote side.<br>
     * A typical example for a place where this is needed is a user interface which has a user
     * click a button and want to see the result of the operation as soon as possible. A detailed
     * example is given at the documentation of the return value below.<br>
     * Notice that even this could be done asynchronously with certain UI frameworks: An event
     * handler could wait asynchronously for the result and fill it in the UI. However, for things
     * such as web interfaces, you might need JavaScript then, so a synchronous call will simplify
     * the code.<br>
     * In addition to only using synchronous calls when absolutely necessary, please make sure to
     * set a timeout parameter which is as small as possible.<br><br>
     * 
     * ATTENTION: While remembering that this function can block for a long time, you have to
     * consider that this class will <b>not</b> call {@link Thread#interrupt()} upon pending calls
     * to this function during shutdown. You <b>must</b> keep track of threads which are executing 
     * this function on your own, and call {@link Thread#interrupt()} upon them at shutdown of your
     * plugin. The interruption will then cause the function to throw {@link InterruptedException}
     * quickly, which your calling threads should obey by exiting to ensure a fast shutdown.<br><br>
     * 
     * ATTENTION: This function can only work properly as long the message which you passed to this
     * function does contain a message identifier which does not collide with one of another
     * message.<br>
     * To ensure this, you <b>must</b> use the constructor {@link FCPPluginMessage#construct(
     * SimpleFieldSet, Bucket)} (or one of its shortcuts) and do not call this function twice upon
     * the same message.<br>
     * If you do not follow this rule and use colliding message identifiers, there might be side
     * effects such as:<br>
     * - This function might return the reply to the colliding message instead of the reply to
     *   your message. Notice that this implicitly means that you cannot be sure anymore that
     *   a message was delivered successfully if this function does not throw.<br>
     * - The reply might be passed to the {@link FredPluginFCPMessageHandler} instead of being
     *   returned from this function.<br>
     * Please notice that both these side effects can also happen if the remote partner erroneously
     * sends multiple replies to the same message identifier.<br>
     * As long as the remote side is implemented using FCPPluginClient as well, and uses it
     * properly, this shouldn't happen though. Thus in general, you should assume that the reply
     * which this function returns <b>is</b> the right one, and your
     * {@link FredPluginFCPMessageHandler} should just drop reply messages which were not expected
     * and log them as at {@link LogLevel#WARNING}. The information here was merely provided to help
     * you with debugging the cause of these events, <b>not</b> to make you change your code
     * to assume that sendSynchronous does not work. For clean code, please write it in a way which
     * assumes that the function works properly.<br><br>
     * 
     * ATTENTION: If you plan to use this inside of message handling functions of your
     * implementations of the interfaces
     * {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler} or
     * {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}, be sure to read the JavaDoc
     * of the message handling functions first as it puts additional constraints on the usage
     * of the FCPPluginClient they receive.<br><br>
     * 
     * @param direction
     *            Whether to send the message to the server or the client message handler.<br><br>
     * 
     *            While you <b>can</b> use this to send messages to yourself, be careful not to
     *            cause thread deadlocks with this. The function will call your message
     *            handler function of {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(
     *            FCPPluginClient, FCPPluginMessage)} in <b>a different thread</b>, so it should not
     *            cause deadlocks on its own, but you might produce deadlocks with your own thread
     *            synchronization measures.<br><br>
     * 
     * @param message
     *            <b>Must be</b> constructed using
     *            {@link FCPPluginMessage#construct(SimpleFieldSet, Bucket)}.<br><br>
     * 
     *            Must <b>not</b> be a reply message: This function needs determine when the remote
     *            side has finished processing the message so it knows when to return. That requires
     *            the remote side to send a reply to indicate that the FCP call is finished.
     *            Replies to replies are not allowed though (to prevent infinite bouncing).<br><br>
     * 
     * @param timeoutNanoSeconds
     *            The function will wait for a reply to arrive for this amount of time.<br>
     *            Must be greater than 0 and below or equal to 1 minute.<br><br>
     * 
     *            If the timeout expires, an {@link IOException} is thrown.<br>
     *            This FCPPluginClient <b>should be</b> considered as dead once this happens, you
     *            should then discard it and obtain a fresh one.<br><br>
     * 
     *            ATTENTION: The sending of the message is not affected by this timeout, it only
     *            affects how long we wait for a reply. The sending is done in another thread, so
     *            if your message is very large, and takes longer to transfer than the timeout
     *            grants, this function will throw before the message has been sent.<br>
     *            Additionally, the sending of the message is <b>not</b> terminated if the timeout
     *            expires before it was fully transferred. Thus, the message can arrive at the
     *            remote side even if this function has thrown, and you might receive an off-thread
     *            reply to the message in the {@link FredPluginFCPMessageHandler}.<br><br>
     *            
     *            Notice: For convenience, use class {@link TimeUnit} to easily convert seconds,
     *            milliseconds, etc. to nanoseconds.<br><br>
     * 
     * @return The reply {@link FCPPluginMessage} which the remote partner sent to your message.
     *         <br><br>
     * 
     *         <b>ATTENTION</b>: Even if this function did not throw, the reply might indicate an
     *         error with the field {link FCPPluginMessage#success}: This can happen if the message
     *         was delivered but the remote message handler indicated that the FCP operation you
     *         initiated failed.<br>
     *         The fields {@link FCPPluginMessage#errorCode} and
     *         {@link FCPPluginMessage#errorMessage} might indicate the type of the error.<br><br>
     * 
     *         This can be used to decide to retry certain operations. A practical example
     *         would be a user trying to create an account at an FCP server application:<br>
     *         - Your UI would use this function to try to create the account by FCP.<br>
     *         - The user might type an invalid character in the username.<br>
     *         - The server could then indicate failure of creating the account by sending a reply
     *           with success == false.<br>
     *         - Your UI could detect the problem by success == false at the reply and an errorCode
     *           of "InvalidUsername". The errorCode can be used to decide to highlight the username
     *           field with a red color.<br>
     *         - The UI then could prompt the user to chose a valid username by displaying the
     *           errorMessage which the server provides to ship a translated, human readable
     *           explanation of what is wrong with the username.<br>
     * @throws IOException
     *             If the given timeout expired before a reply was received <b>or</b> if the
     *             connection has been closed before even sending the message.<br>
     *             This FCPPluginClient <b>should be</b> considered as dead once this happens, you
     *             should then discard it and obtain a fresh one.
     * @throws InterruptedException
     *             If another thread called {@link Thread#interrupt()} upon the thread which you
     *             used to execute this function.<br>
     *             This is a shutdown mechanism: You can use it to abort a call to this function
     *             which is waiting for the timeout to expire.<br><br>
     * @see FCPPluginClient#synchronousSends
     *          An overview of how synchronous sends and especially their threading work internally
     *          is provided at the map which stores them.
     * @see #send(SendDirection, FCPPluginMessage)
     *          The non-blocking, asynchronous send() should be used instead of this whenever
     *          possible.
     */
    public FCPPluginMessage sendSynchronous(
        SendDirection direction, FCPPluginMessage message, long timeoutNanoSeconds)
            throws IOException, InterruptedException;

    /** @return A verbose String containing the internal state. Useful for debug logs. */
    public String toString();

}