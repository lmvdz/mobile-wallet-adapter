/*
 * Copyright (c) 2022 Solana Labs, Inc.
 */

package com.solana.mobilewalletadapter.walletlib.association;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.solana.mobilewalletadapter.common.AssociationContract;
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterServer;
import com.solana.mobilewalletadapter.walletlib.scenario.Scenario;

public class RemoteAssociationUri extends AssociationUri {
    @NonNull
    public final String reflectorHostAuthority;

    public final long reflectorId;

    public RemoteAssociationUri(@NonNull Uri uri) {
        super(uri);
        validate(uri);
        reflectorHostAuthority = parseReflectorHostAuthority(uri);
        reflectorId = parseReflectorId(uri);
    }

    private static void validate(@NonNull Uri uri) {
        if (!uri.getPath().endsWith(AssociationContract.REMOTE_PATH_SUFFIX)) {
            throw new IllegalArgumentException("uri must end with " +
                    AssociationContract.REMOTE_PATH_SUFFIX);
        }
    }

    @NonNull
    @Override
    public Scenario createScenario(@NonNull Scenario.Callbacks callbacks,
                                   @NonNull MobileWalletAdapterServer.MethodHandlers methodHandlers) {
        throw new UnsupportedOperationException("Remote association is not yet implemented");
    }

    @NonNull
    private static String parseReflectorHostAuthority(@NonNull Uri uri) {
        final String reflectorHostAuthority = uri.getQueryParameter(
                AssociationContract.REMOTE_PARAMETER_REFLECTOR_HOST_AUTHORITY);
        if (reflectorHostAuthority == null || reflectorHostAuthority.isEmpty()) {
            throw new IllegalArgumentException("Reflector host authority must be specified");
        }

        return reflectorHostAuthority;
    }

    private static long parseReflectorId(@NonNull Uri uri) {
        final String reflectorIdStr = uri.getQueryParameter(
                AssociationContract.REMOTE_PARAMETER_REFLECTOR_ID);
        if (reflectorIdStr == null) {
            throw new IllegalArgumentException("Reflector ID parameter must be specified");
        }

        final long reflectorId;
        try {
            reflectorId = Long.parseLong(reflectorIdStr, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Reflector ID parameter must be a long", e);
        }

        return reflectorId;
    }
}