package pro.dbro.airshare.session;

import android.content.Context;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import pro.dbro.airshare.transport.Transport;
import pro.dbro.airshare.transport.ble.BLETransport;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class SessionManager implements Transport.TransportCallback,
                                       SessionMessageDeserializer.SessionMessageDeserializerCallback,
                                       SessionMessageScheduler {

    public interface SessionManagerCallback {

        public void peerStatusUpdated(Peer peer, Transport.ConnectionStatus newStatus, boolean isHost);

        public void messageReceivingFromPeer(SessionMessage message, Peer recipient, float progress);

        public void messageReceivedFromPeer(SessionMessage message, Peer recipient);

        public void messageSendingToPeer(SessionMessage message, Peer recipient, float progress);

        public void messageSentToPeer(SessionMessage message, Peer recipient, Exception exception);

    }

    private Context                                   context;
    private String                                    serviceName;
    private Set<Transport>                            transports;
    private LocalPeer                                 localPeer;
    private IdentityMessage                           localIdentityMessage;
    private SessionManagerCallback                    callback;
    private HashMap<String, SortedSet<Transport>>     identifierTransports  = new HashMap<>();
    private BiMap<String, SessionMessageDeserializer> identifierReceivers   = HashBiMap.create();
    private BiMap<String, SessionMessageSerializer>   identifierSenders     = HashBiMap.create();
    private final BiMap<String, Peer>                 identifiedPeers       = HashBiMap.create();
    private Set<String>                               identifyingPeers      = new HashSet<>();
    private Set<String>                               hostIdentifiers       = new HashSet<>();

    private final Object lock = new Object();

    // <editor-fold desc="Public API">

    public SessionManager(Context context,
                          String serviceName,
                          LocalPeer localPeer,
                          SessionManagerCallback callback) {

        this.context     = context;
        this.serviceName = serviceName;
        this.localPeer   = localPeer;
        this.callback    = callback;

        localIdentityMessage = new IdentityMessage(this.localPeer);

        initializeTransports(serviceName);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void advertiseLocalPeer() {
        for (Transport transport : transports)
            transport.advertise();
    }

    public void scanForPeers() {
        for (Transport transport : transports)
            transport.scanForPeers();
    }

    /**
     * Send a message to the given recipient. If the recipient is not currently available,
     * delivery will occur next time the peer is available
     */
    public void sendMessage(SessionMessage message, Peer recipient) {

        String recipientIdentifier = identifiedPeers.inverse().get(recipient);

        if (recipientIdentifier == null) {
            Timber.e("No Identifier for peer %s", recipient.getAlias());
            return;
        }

        Transport transport = getPreferredTransportForPeer(recipient);

        if (!identifierSenders.containsKey(recipientIdentifier))
            identifierSenders.put(recipientIdentifier, new SessionMessageSerializer(message));
        else
            identifierSenders.get(recipientIdentifier).queueMessage(message);

        SessionMessageSerializer sender = identifierSenders.get(recipientIdentifier);

        if (transport != null)
            transport.sendData(sender.getNextChunk(transport.getMtuForIdentifier(recipientIdentifier)),
                               recipientIdentifier);
        else
            Timber.d("Send queued. No transport available for identifier %s", recipientIdentifier);

        // If no transport for the peer is available, data will be sent next time peer is available
    }

    public Set<Peer> getAvailablePeers() {
        return identifiedPeers.values();
    }

    public void stop() {
        for (Transport transport : transports)
            transport.stop();
    }

    // </editor-fold desc="Public API">

    // <editor-fold desc="Private API">

    private void initializeTransports(String serviceName) {
        transports = new HashSet<>();
        transports.add(new BLETransport(context, serviceName, this));
//        transports.add(new WifiTransport(context, serviceName, this));
    }

    private @Nullable Transport getPreferredTransportForPeer(Peer peer) {

        String recipientIdentifier = identifiedPeers.inverse().get(peer);

        if (!identifiedPeers.containsValue(peer) ||
            !identifierTransports.containsKey(recipientIdentifier))
                return null;

        // Return the Transport with the highest value (largest MTU)
        return identifierTransports.get(recipientIdentifier)
                                   .last();
    }

    private boolean shouldIdentifyPeer(String identifier) {
        // TODO : Might have banned peers etc.
        return !identifyingPeers.contains(identifier);
    }

    private void registerTransportForIdentifier(Transport transport, String identifier) {
        if (!identifierTransports.containsKey(identifier))
            identifierTransports.put(identifier, new TreeSet<Transport>());

        boolean newTransport = identifierTransports.get(identifier).add(transport);
        if (newTransport)
            Timber.d("Transport added for identifier %s", newTransport, identifier);
    }

    // </editor-fold desc="Private API">

    // <editor-fold desc="TransportCallback">

    @Override
    public void dataReceivedFromIdentifier(Transport transport, byte[] data, String identifier) {

        // An asymmetric transport may not receive connection events
        // so we use this opportunity to associate the identifier with its transport
        registerTransportForIdentifier(transport, identifier);

        if (!identifierReceivers.containsKey(identifier))
            identifierReceivers.put(identifier, new SessionMessageDeserializer(context, this));

        identifierReceivers.get(identifier)
                           .dataReceived(data);
    }

    @Override
    public void dataSentToIdentifier(Transport transport, byte[] data, String identifier, Exception exception) {

        synchronized (lock) {

            if (exception != null) {
                Timber.w("Data failed to send to %s", identifier);
                return;
            }

            SessionMessageSerializer sender = identifierSenders.get(identifier);

            if (sender == null) {
                Timber.w("No sender for dataSentToIdentifier to %s", identifier);
                return;
            }

            Pair<SessionMessage, Float> messagePair = sender.ackChunkDelivery();

            if (messagePair != null) {

                SessionMessage message = messagePair.first;
                float progress = messagePair.second;

                Timber.d("%d %s bytes (%.0f pct) sent to %s",
                        data.length,
                        message.getType(),
                        progress * 100,
                        identifier);

                if (progress == 1 && message.equals(localIdentityMessage)) {
                    Timber.d("Local identity acknowledged by recipient");
                    identifyingPeers.add(identifier);
                }

                Peer recipient = identifiedPeers.get(identifier);
                if (recipient != null) {
                    if (progress == 1) {

                        callback.messageSentToPeer(message,
                                identifiedPeers.get(identifier),
                                null);

                    } else {

                        callback.messageSendingToPeer(message,
                                identifiedPeers.get(identifier),
                                progress);
                    }
                } else
                    Timber.w("Cannot report %s message send, %s not yet identified",
                        message.getType(), identifier);

                byte[] toSend = sender.getNextChunk(transport.getMtuForIdentifier(identifier));
                if (toSend != null)
                    transport.sendData(toSend, identifier);
            } else
                Timber.w("No current message corresponding to dataSentToIdentifier");
        }
    }

    @Override
    public void identifierUpdated(Transport transport,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  boolean isHost,
                                  Map<String, Object> extraInfo) {
        switch(status) {
            case CONNECTED:
                Timber.d("Connected to %s", identifier);
                if (isHost) hostIdentifiers.add(identifier);

                if (shouldIdentifyPeer(identifier)) {
                    if (!identifierSenders.containsKey(identifier)) {
                        Timber.d("Queuing identity to %s", identifier);
                        identifierSenders.put(identifier, new SessionMessageSerializer(localIdentityMessage));
                    } else
                        Timber.w("Outgoing messages already exist for unidentified peer %s", identifier);
                }

                registerTransportForIdentifier(transport, identifier);

                // Send outgoing messages to peer
                SessionMessageSerializer sender = identifierSenders.get(identifier);

                if (sender.getCurrentMessage() != null) {

                    boolean sendingIdentity = sender.getCurrentMessage() instanceof IdentityMessage;

                    byte[] toSend = sender.getNextChunk(transport.getMtuForIdentifier(identifier));
                    if (toSend == null) return;

                    if (transport.sendData(toSend, identifier)) {
                        if (sendingIdentity) {
                            Timber.d("Sent identity to %s", identifier);
                        }
                    } else
                        Timber.w("Failed to send %s message to new peer %s",
                                sender.getCurrentMessage().getType(),
                                identifier);
                }

                break;

            case DISCONNECTED:
                if (isHost) hostIdentifiers.remove(identifier);

                Timber.d("Disconnected from %s", identifier);

                if (identifiedPeers.containsKey(identifier))
                    callback.peerStatusUpdated(identifiedPeers.get(identifier),
                                               Transport.ConnectionStatus.DISCONNECTED,
                                               isHost);
                else
                    Timber.w("Could not report disconnection, peer not identified");

                identifyingPeers.remove(identifier);
                identifiedPeers.remove(identifier);
                identifierSenders.remove(identifier);
                identifierReceivers.remove(identifier);
                break;
        }
    }

    // </editor-fold desc="TransportCallback">

    // <editor-fold desc="SessionMessageReceiverCallback">

    @Override
    public void onHeaderReady(SessionMessageDeserializer receiver, SessionMessage message) {

        String senderIdentifier = identifierReceivers.inverse().get(receiver);
        Timber.d("Received header for %s message from %s", message.getType(), senderIdentifier);
    }

    @Override
    public void onBodyProgress(SessionMessageDeserializer receiver, SessionMessage message, float progress) {

        String senderIdentifier = identifierReceivers.inverse().get(receiver);
        Timber.d("Received %s message with progress %f from %s", message.getType(), progress, senderIdentifier);

        callback.messageReceivingFromPeer(message, identifiedPeers.get(senderIdentifier), progress);
    }

    @Override
    public void onComplete(SessionMessageDeserializer receiver, SessionMessage message, Exception e) {

        synchronized (lock) {

            String senderIdentifier = identifierReceivers.inverse().get(receiver);

            if (e == null) {

                Timber.d("Received complete %s message from %s", message.getType(), senderIdentifier);

                if (message instanceof IdentityMessage) {

                    Peer peer = ((IdentityMessage) message).getPeer();

                    boolean sentIdentityToSender = identifyingPeers.contains(senderIdentifier);
                    boolean newIdentity = !identifiedPeers.containsKey(senderIdentifier);

                    identifyingPeers.remove(senderIdentifier);
                    identifiedPeers.put(senderIdentifier, peer);

                    if (newIdentity) {
                        Timber.d("Received identity for %s. %s", senderIdentifier, sentIdentityToSender ? "" : "Responding with own.");
                        // As far as upper layers are concerned, connection events occur when the remote
                        // peer is identified.
                        if (!sentIdentityToSender) sendMessage(localIdentityMessage, peer);
                        callback.peerStatusUpdated(peer, Transport.ConnectionStatus.CONNECTED, hostIdentifiers.contains(senderIdentifier));
                    }

                } else if (identifiedPeers.containsKey(senderIdentifier)) {

                    callback.messageReceivedFromPeer(message, identifiedPeers.get(senderIdentifier));

                } else {

                    Timber.w("Received complete non-identity message from unidentified peer");
                }

            } else {
                Timber.d("Incoming message from %s failed with error '%s'", senderIdentifier, e.getLocalizedMessage());
                e.printStackTrace();
            }
        }
    }

    // </editor-fold desc="SessionMessageReceiverCallback">

}
