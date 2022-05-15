/*
 * Copyright (c) 2022 Solana Labs, Inc.
 */

package com.solana.mobilewalletadapter.common.protocol;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.nimbusds.jose.CompressionAlgorithm;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.impl.ConcatKDF;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public abstract class MobileWalletAdapterSessionCommon implements MessageReceiver, MessageSender {
    private static final String TAG = MobileWalletAdapterSessionCommon.class.getSimpleName();

    @NonNull
    private final MessageReceiver mDecryptedPayloadReceiver;
    private final StateCallbacks mStateCallbacks;
    private final EncryptionMethod mPayloadEncryptionMethod;
    private final boolean mDeflatePayload;

    protected MessageSender mMessageSender;
    @NonNull
    private State mState = State.WAITING_FOR_CONNECTION;
    private KeyPair mECDHKeypair;
    private byte[] mECDHSecret;
    private EncryptionMethod mCachedEncryptionMethod;
    private SecretKey mCachedEncryptionKey;

    protected MobileWalletAdapterSessionCommon(@NonNull MessageReceiver decryptedPayloadReceiver,
                                               @Nullable StateCallbacks stateCallbacks,
                                               @Nullable PayloadEncryptionMethod payloadEncryptionMethod,
                                               boolean deflatePayload) {
        mDecryptedPayloadReceiver = decryptedPayloadReceiver;
        mStateCallbacks = stateCallbacks;
        mPayloadEncryptionMethod = convertPayloadEncryptionMethodType(payloadEncryptionMethod);
        mDeflatePayload = deflatePayload;
    }

    @Nullable
    private static EncryptionMethod convertPayloadEncryptionMethodType(
            @Nullable PayloadEncryptionMethod payloadEncryptionMethod) {
        if (payloadEncryptionMethod == null) {
            return null;
        }

        switch (payloadEncryptionMethod) {
            case AES128_GCM:
                return EncryptionMethod.A128GCM;
            case AES256_GCM:
                return EncryptionMethod.A256GCM;
            default:
                throw new IllegalStateException("Missing switch case");
        }
    }

    @Override
    public synchronized void receiverConnected(@NonNull MessageSender messageSender) {
        Log.v(TAG, "receiverConnected");
        assert(mState == State.WAITING_FOR_CONNECTION);
        mState = State.SESSION_ESTABLISHMENT;
        mMessageSender = messageSender;
        onReceiverConnected();
    }

    protected void onReceiverConnected() {}

    @Override
    public synchronized void receiverDisconnected() {
        Log.v(TAG, "receiverDisconnected");
        assert(mState != State.WAITING_FOR_CONNECTION);

        // N.B. mState can be State.CLOSED if onSessionError was previously invoked
        if (mState != State.CLOSED) {
            doClose();
            Log.i(TAG, "mobile-wallet-adapter session closed");

            if (mStateCallbacks != null) {
                mStateCallbacks.onSessionClosed();
            }
        }
    }

    protected void onSessionError() {
        Log.v(TAG, "onSessionError");
        assert(mState == State.SESSION_ESTABLISHMENT || mState == State.ENCRYPTED_SESSION);

        doClose();
        Log.w(TAG, "mobile-wallet-adapter session closed due to error");

        if (mStateCallbacks != null) {
            mStateCallbacks.onSessionError();
        }
    }

    private void doClose() {
        mState = State.CLOSED;
        mMessageSender = null;
        mECDHKeypair = null;
        mECDHSecret = null;
        mCachedEncryptionMethod = null;
        mCachedEncryptionKey = null;
        mDecryptedPayloadReceiver.receiverDisconnected();
    }

    @Override
    public synchronized void receiverMessageReceived(@NonNull byte[] payload) {
        Log.v(TAG, "receiverMessageReceived: size=" + payload.length);

        // ignore empty PING messages in all states
        if (payload.length == 0) {
            return;
        }

        try {
            switch (mState) {
                case WAITING_FOR_CONNECTION:
                    throw new IllegalStateException("Received a message before connection");
                case SESSION_ESTABLISHMENT:
                    handleSessionEstablishmentMessage(payload);
                    break;
                case ENCRYPTED_SESSION:
                    handleEncryptedSessionPayload(payload);
                    break;
                case CLOSED:
                    Log.w(TAG, "message received after closed, ignoring");
                    break;
            }
        } catch (SessionMessageException e) {
            Log.e(TAG, "Invalid message received; terminating session", e);
            onSessionError();
        }
    }

    protected abstract void handleSessionEstablishmentMessage(@NonNull byte[] payload)
            throws SessionMessageException;

    private void handleEncryptedSessionPayload(@NonNull byte[] encryptedPayload)
            throws SessionMessageException {
        Log.v(TAG, "handleEncryptedSessionMessage");
        final byte[] payload = decryptSessionPayload(encryptedPayload);
        mDecryptedPayloadReceiver.receiverMessageReceived(payload);
    }

    @Override
    public void send(@NonNull byte[] message) throws IOException {
        final byte[] encryptedPayload;

        Log.v(TAG, "send");

        synchronized (this) {
            if (mState != State.ENCRYPTED_SESSION) {
                throw new IOException("Cannot send in " + mState);
            }

            encryptedPayload = encryptSessionPayload(message, mPayloadEncryptionMethod,
                    mDeflatePayload);
        }

        // Don't hold lock when calling into sender; it could lead to lock-ordering deadlocks.
        mMessageSender.send(encryptedPayload);
    }

    @NonNull
    protected byte[] encryptSessionPayload(@NonNull byte[] payload,
                                           @Nullable EncryptionMethod encryptionMethod,
                                           boolean deflate) {
        if (mECDHSecret == null) {
            throw new IllegalStateException("Cannot decrypt, no ECDH session secret has been established");
        }

        if (encryptionMethod == null) {
            encryptionMethod = mCachedEncryptionMethod;
            if (encryptionMethod == null) {
                encryptionMethod = EncryptionMethod.A128GCM;
            }
        }

        final JWEHeader jweHeader = new JWEHeader.Builder(JWEAlgorithm.DIR, encryptionMethod)
                .compressionAlgorithm(deflate ? CompressionAlgorithm.DEF : null)
                .build();

        final JWEObject jwe = new JWEObject(jweHeader, new Payload(payload));

        updateCachedEncryptionKey(encryptionMethod);

        try {
            final JWEEncrypter jweEncrypter = new DirectEncrypter(mCachedEncryptionKey);
            jwe.encrypt(jweEncrypter);
        } catch (JOSEException | IllegalStateException e) {
            throw new UnsupportedOperationException("Failed encrypting payload", e);
        }

        return jwe.serialize().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    protected byte[] decryptSessionPayload(@NonNull byte[] payload) throws SessionMessageException {
        if (mECDHSecret == null) {
            throw new IllegalStateException("Cannot decrypt, no ECDH session secret has been established");
        }

        final JWEObject jwe;
        try {
            jwe = JWEObject.parse(decodeAsUtf8String(payload));
        } catch (CharacterCodingException e) {
            throw new SessionMessageException("Error UTF-8 decoding encrypted session message wrapper", e);
        } catch (ParseException e) {
            throw new SessionMessageException("Error parsing encrypted session message wrapper as JWE", e);
        }

        final JWEHeader jweHeader = jwe.getHeader();
        if (jweHeader.getAlgorithm() != JWEAlgorithm.DIR ||
                (jweHeader.getEncryptionMethod() != EncryptionMethod.A128GCM &&
                        jweHeader.getEncryptionMethod() != EncryptionMethod.A256GCM) ||
                (jweHeader.getCompressionAlgorithm() != null &&
                        jweHeader.getCompressionAlgorithm() != CompressionAlgorithm.DEF)
        ) {
            throw new SessionMessageException("JWE encrypted message wrapper parameters are incorrect");
        }

        updateCachedEncryptionKey(jweHeader.getEncryptionMethod());

        try {
            final JWEDecrypter jweDecrypter = new DirectDecrypter(mCachedEncryptionKey);
            jwe.decrypt(jweDecrypter);
        } catch (JOSEException | IllegalStateException e) {
            throw new SessionMessageException("Failed decrypting payload", e);
        }

        return jwe.getPayload().toBytes();
    }

    private void updateCachedEncryptionKey(@NonNull EncryptionMethod encryptionMethod) {
        if (mCachedEncryptionMethod != encryptionMethod) {
            Log.v(TAG, "Updating cached encryption method to " + encryptionMethod);
            mCachedEncryptionMethod = encryptionMethod;

            try {
                mCachedEncryptionKey = new ConcatKDF("SHA-256").deriveKey(
                        new SecretKeySpec(mECDHSecret, "AES"),
                        encryptionMethod.cekBitLength(),
                        encryptionMethod.getName().getBytes(StandardCharsets.UTF_8),
                        null,
                        null,
                        ConcatKDF.encodeIntData(encryptionMethod.cekBitLength()),
                        null);
            } catch (JOSEException e) {
                throw new UnsupportedOperationException("ConcatKDF key derivation failed", e);
            }
        }
    }

    @NonNull
    protected static KeyPair generateECP256KeyPair() {
        try {
            final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(Curve.P_256.toECParameterSpec());
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new UnsupportedOperationException("Failed generating an EC P-256 keypair for ECDH", e);
        }
    }

    @NonNull
    protected ECPublicKey generateSessionECDHKeyPair() {
        Log.v(TAG, "generateSessionECDHKeyPair");

        if (mState != State.SESSION_ESTABLISHMENT) {
            throw new IllegalStateException("Incorrect state for generating session ECDH keypair");
        }

        mECDHKeypair = generateECP256KeyPair();
        return (ECPublicKey) mECDHKeypair.getPublic();
    }

    protected void generateSessionECDHSecret(@NonNull ECPublicKey otherPublicKey) {
        Log.v(TAG, "generateSessionECDHSecret");

        if (mState != State.SESSION_ESTABLISHMENT) {
            throw new IllegalStateException("Incorrect state for generating session ECDH secret");
        }

        try {
            final KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(mECDHKeypair.getPrivate());
            keyAgreement.doPhase(otherPublicKey, true);
            mECDHSecret = keyAgreement.generateSecret();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new UnsupportedOperationException("Failed generating an ECDH secret", e);
        }

        mState = State.ENCRYPTED_SESSION;

        Log.i(TAG, "Encrypted session established");

        mDecryptedPayloadReceiver.receiverConnected(this);

        if (mStateCallbacks != null) {
            mStateCallbacks.onSessionEstablished();
        }
    }

    @NonNull
    protected static JWK createJWKForECP256(@NonNull ECPublicKey publicKey,
                                            @NonNull KeyOperation op) {
        final ArraySet<KeyOperation> keyOp = new ArraySet<>(1);
        keyOp.add(op);
        try {
            return new ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256, publicKey)
                    .keyOperations(keyOp)
                    .build();
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("publicKey must be a valid EC P-256 public key", e);
        }
    }

    protected static boolean isJWKSuitableForP256ECDH(@NonNull JWK jwk) {
        return (jwk.getKeyType() == KeyType.EC &&
                jwk.getKeyOperations() != null &&
                jwk.getKeyOperations().size() == 1 &&
                jwk.getKeyOperations().contains(KeyOperation.DERIVE_KEY) &&
                jwk.toECKey().getCurve() == Curve.P_256 &&
                !jwk.isPrivate());
    }

    @NonNull
    protected static String decodeAsUtf8String(@NonNull byte[] b) throws CharacterCodingException {
        final CharsetDecoder utf8Dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        final ByteBuffer bb = ByteBuffer.wrap(b);
        return utf8Dec.decode(bb).toString();
    }

    protected static class SessionMessageException extends Exception {
        public SessionMessageException() {}
        public SessionMessageException(@Nullable String message) { super(message); }
        public SessionMessageException(@Nullable String message, @Nullable Throwable cause) { super(message, cause); }
    }

    private enum State {
        WAITING_FOR_CONNECTION, SESSION_ESTABLISHMENT, ENCRYPTED_SESSION, CLOSED
    }

    public enum PayloadEncryptionMethod {
        AES128_GCM, AES256_GCM
    }

    public interface StateCallbacks {
        /** Session has been fully established, and is ready for use */
        void onSessionEstablished();

        /**
         * Session has been closed normally, and should no longer be used.
         * <p/>Note that there is no guarantee {@link #onSessionEstablished()} will have been
         * invoked when this is called; a session can be closed during establishment by either
         * party.
         */
        void onSessionClosed();

        /**
         * An unrecoverable error occurred during session establishment or operation. This session
         * should no longer be used.
         * <p/>Note that this is a terminal indication; {@link #onSessionClosed()} will not be
         * invoked for this session.
         */
        void onSessionError();
    }
}