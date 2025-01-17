/*
 * Copyright (c) 2022 Solana Mobile Inc.
 */

package com.solana.mobilewalletadapter.fakewallet.ui.signpayload

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.solana.mobilewalletadapter.fakewallet.MobileWalletAdapterViewModel
import com.solana.mobilewalletadapter.fakewallet.MobileWalletAdapterViewModel.MobileWalletAdapterServiceRequest
import com.solana.mobilewalletadapter.fakewallet.R
import com.solana.mobilewalletadapter.fakewallet.databinding.FragmentSignPayloadBinding
import kotlinx.coroutines.launch

class SignPayloadFragment : Fragment() {
    private val activityViewModel: MobileWalletAdapterViewModel by activityViewModels()
    private lateinit var viewBinding: FragmentSignPayloadBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewBinding = FragmentSignPayloadBinding.inflate(layoutInflater, container, false)
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                activityViewModel.mobileWalletAdapterServiceEvents.collect { request ->
                    when (request) {
                        is MobileWalletAdapterServiceRequest.SignPayload -> {
                            val res =
                                if (request is MobileWalletAdapterServiceRequest.SignTransaction) {
                                    R.string.label_sign_transaction
                                } else {
                                    R.string.label_sign_message
                                }
                            viewBinding.textSignPayloads.setText(res)
                            viewBinding.textNumTransactions.text =
                                request.request.payloads.size.toString()

                            viewBinding.btnAuthorize.setOnClickListener {
                                activityViewModel.signPayloadSimulateSign(request)
                            }

                            viewBinding.btnDecline.setOnClickListener {
                                activityViewModel.signPayloadDeclined(request)
                            }

                            viewBinding.btnSimulateReauthorize.setOnClickListener {
                                activityViewModel.signPayloadSimulateReauthorizationRequired(request)
                            }

                            viewBinding.btnSimulateAuthorizationFailed.setOnClickListener {
                                activityViewModel.signPayloadSimulateAuthTokenInvalid(request)
                            }

                            viewBinding.btnSimulateInvalidPayload.setOnClickListener {
                                activityViewModel.signPayloadSimulateInvalidPayload(request)
                            }

                            viewBinding.btnSimulateTooManyPayloads.setOnClickListener {
                                activityViewModel.signPayloadSimulateTooManyPayloads(request)
                            }
                        }
                        is MobileWalletAdapterServiceRequest.SignAndSendTransaction -> {
                            request.signatures?.run {
                                // When signatures are present, move on to sending the transaction
                                findNavController().navigate(SignPayloadFragmentDirections.actionSendTransaction())
                                return@collect
                            }

                            viewBinding.textSignPayloads.setText(R.string.label_sign_transaction)
                            viewBinding.textNumTransactions.text =
                                request.request.payloads.size.toString()

                            viewBinding.btnAuthorize.setOnClickListener {
                                activityViewModel.signAndSendTransactionSimulateSign(request)
                            }

                            viewBinding.btnDecline.setOnClickListener {
                                activityViewModel.signAndSendTransactionDeclined(request)
                            }

                            viewBinding.btnSimulateReauthorize.setOnClickListener {
                                activityViewModel.signAndSendTransactionSimulateReauthorizationRequired(request)
                            }

                            viewBinding.btnSimulateAuthorizationFailed.setOnClickListener {
                                activityViewModel.signAndSendTransactionSimulateAuthTokenInvalid(request)
                            }

                            viewBinding.btnSimulateInvalidPayload.setOnClickListener {
                                activityViewModel.signAndSendTransactionSimulateInvalidPayload(request)
                            }

                            viewBinding.btnSimulateTooManyPayloads.setOnClickListener {
                                activityViewModel.signAndSendTransactionSimulateTooManyPayloads(request)
                            }
                        }
                        else -> {
                            // If several events are emitted back-to-back (e.g. during session
                            // teardown), this fragment may not have had a chance to transition
                            // lifecycle states. Only navigate if we believe we are still here.
                            findNavController().let { nc ->
                                if (nc.currentDestination?.id == R.id.fragment_sign_payload) {
                                    nc.navigate(SignPayloadFragmentDirections.actionSignPayloadComplete())
                                }
                            }
                        }
                    }
                }
            }
        }


    }
}