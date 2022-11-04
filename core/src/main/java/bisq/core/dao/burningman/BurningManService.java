/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.burningman;

import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.dao.governance.param.Param;
import bisq.core.dao.governance.period.CycleService;
import bisq.core.dao.governance.proofofburn.ProofOfBurnConsensus;
import bisq.core.dao.governance.proposal.MyProposalListService;
import bisq.core.dao.governance.proposal.ProposalService;
import bisq.core.dao.governance.proposal.storage.appendonly.ProposalPayload;
import bisq.core.dao.state.DaoStateListener;
import bisq.core.dao.state.DaoStateService;
import bisq.core.dao.state.model.blockchain.BaseTx;
import bisq.core.dao.state.model.blockchain.Block;
import bisq.core.dao.state.model.blockchain.Tx;
import bisq.core.dao.state.model.blockchain.TxOutput;
import bisq.core.dao.state.model.governance.CompensationProposal;
import bisq.core.dao.state.model.governance.Cycle;
import bisq.core.dao.state.model.governance.Issuance;
import bisq.core.dao.state.model.governance.IssuanceType;
import bisq.core.dao.state.model.governance.Proposal;
import bisq.core.dao.state.model.governance.ReimbursementProposal;

import bisq.network.p2p.storage.P2PDataStorage;

import bisq.common.util.Hex;
import bisq.common.util.Tuple2;
import bisq.common.util.Utilities;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class BurningManService implements DaoStateListener {
    public static final Date ACTIVATION_DATE = Utilities.getUTCDate(2022, GregorianCalendar.OCTOBER, 25);
    private static final double GENESIS_OUTPUT_AMOUNT_FACTOR = 0.1;    // Means amounts at genesis height gets reduced to 10%
    private static final String GENESIS_OUTPUT_PREFIX = "Bisq co-founder ";
    private static final int MAX_COMP_REQUEST_AGE = 105120;             // 2 years in blocks (2*365*144)     // 312 for regtest with 2*12*13 (13 is block cycle)
    private static final int MAX_BURN_AMOUNT_AGE = 52560;               // 1 years in blocks (365*144)       // 156 for regtest with 12*13 (13 is block cycle)
    private static final int NUM_REIMBURSEMENT_CYCLES = 12;             // We use reimbursements of the past 12 cycles
    private static final long DEFAULT_EXPECTED_BTC_FEES = 6200000;      // Average BTC trade fee revenue per month in BSQ value. Can be changed with DAO parameter voting
    static final double ISSUANCE_BOOST_FACTOR = 2;                      // Contributor can burn 2 times the amount from their issuance share
    static final long BURN_TARGET_BOOST_AMOUNT = 10000000;              // Burn target gets increased by that amount to give more flexibility.

    public static boolean isActivated() {
        return new Date().after(ACTIVATION_DATE);
    }

    private final DaoStateService daoStateService;
    private final CycleService cycleService;
    private final ProposalService proposalService;
    private final MyProposalListService myProposalListService;
    private final BsqWalletService bsqWalletService;

    private final Map<String, BurningManCandidate> burningManCandidatesByName = new HashMap<>();
    private final Set<ReimbursementModel> reimbursements = new HashSet<>();
    private int currentChainHeight;
    private Optional<Long> burnTarget = Optional.empty();

    @Inject
    public BurningManService(DaoStateService daoStateService,
                             CycleService cycleService,
                             ProposalService proposalService,
                             MyProposalListService myProposalListService,
                             BsqWalletService bsqWalletService) {
        this.daoStateService = daoStateService;
        this.cycleService = cycleService;
        this.proposalService = proposalService;
        this.myProposalListService = myProposalListService;
        this.bsqWalletService = bsqWalletService;

        daoStateService.addDaoStateListener(this);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoStateListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onParseBlockCompleteAfterBatchProcessing(Block block) {
        burningManCandidatesByName.clear();
        reimbursements.clear();
        currentChainHeight = block.getHeight();
        burnTarget = Optional.empty();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BurningManCandidates
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Based on current block height
    public Map<String, BurningManCandidate> getCurrentBurningManCandidatesByName() {
        // Cached value is only used for currentChainHeight
        if (!burningManCandidatesByName.isEmpty()) {
            return burningManCandidatesByName;
        }

        burningManCandidatesByName.putAll(getBurningManCandidatesByName(currentChainHeight));
        return burningManCandidatesByName;
    }

    // Allows recreation of data model for the given chainHeight
    public Map<String, BurningManCandidate> getBurningManCandidatesByName(int chainHeight) {
        Map<String, BurningManCandidate> burningManCandidatesByName = new HashMap<>();
        Map<P2PDataStorage.ByteArray, Set<TxOutput>> proofOfBurnOpReturnTxOutputByHash = getProofOfBurnOpReturnTxOutputByHash(chainHeight);

        // Add contributors who made a compensation request
        daoStateService.getIssuanceSetForType(IssuanceType.COMPENSATION).stream()
                .filter(issuance -> issuance.getChainHeight() <= chainHeight)
                .forEach(issuance -> {
                            getCompensationProposalsForIssuance(issuance).forEach(compensationProposal -> {
                                String name = compensationProposal.getName();
                                burningManCandidatesByName.putIfAbsent(name, new BurningManCandidate());
                                BurningManCandidate candidate = burningManCandidatesByName.get(name);

                                // Issuance
                                compensationProposal.getBurningManReceiverAddress()
                                        .or(() -> daoStateService.getTx(compensationProposal.getTxId())
                                                .map(tx -> {
                                                    ImmutableList<TxOutput> txOutputs = tx.getTxOutputs();
                                                    // The compensation request tx has usually 4 outputs. If there is no BTC change its 3 outputs.
                                                    // BTC change output is at index 2 if present otherwise
                                                    // we use the BSQ address of the compensation candidate output at index 1.
                                                    // See https://docs.bisq.network/dao-technical-overview.html#compensation-request-txreimbursement-request-tx
                                                    if (txOutputs.size() == 4) {
                                                        return txOutputs.get(2).getAddress();
                                                    } else {
                                                        return txOutputs.get(1).getAddress();
                                                    }
                                                }))
                                        .ifPresent(address -> {
                                            int issuanceHeight = issuance.getChainHeight();
                                            long issuanceAmount;

                                            // There was a reimbursement for a conference sponsorship with 44776 BSQ. We remove that as well.
                                            // See https://github.com/bisq-network/compensation/issues/498?utm_source=desktop-client&utm_medium=in-app-link&utm_campaign=language_en
                                            boolean wasSponsorship = issuance.getTxId().equals("01455fc4c88fca0665a5f56a90ff03fb9e3e88c3430ffc5217246e32d180aa64");
                                            if (wasSponsorship) {
                                                issuanceAmount = 119400; // That was the compensation part
                                            } else {
                                                issuanceAmount = issuance.getAmount();
                                            }
                                            int cycleIndex = getCycleIndex(issuanceHeight);
                                            // Up to cycle 15 the RefundAgent made reimbursement requests as compensation requests. We filter out those entries.
                                            // As it is mixed with RefundAgents real compensation requests we take out all above 3500 BSQ.
                                            boolean isReimbursementOfRefundAgent = name.equals("RefundAgent") && cycleIndex <= 15 && issuanceAmount > 350000;
                                            if (!isReimbursementOfRefundAgent) {
                                                long decayedIssuanceAmount = getDecayedCompensationAmount(issuanceAmount, issuanceHeight, chainHeight);
                                                long issuanceDate = daoStateService.getBlockTime(issuanceHeight);
                                                candidate.addCompensationModel(new CompensationModel(address,
                                                        issuanceAmount,
                                                        decayedIssuanceAmount,
                                                        issuanceHeight,
                                                        issuance.getTxId(),
                                                        issuanceDate,
                                                        cycleIndex));
                                            }
                                        });

                                addBurnOutputModel(chainHeight, proofOfBurnOpReturnTxOutputByHash, name, candidate);
                            });
                        }
                );

        // Add output receivers of genesis transaction
        daoStateService.getGenesisTx()
                .ifPresent(tx -> tx.getTxOutputs().forEach(txOutput -> {
                    String name = GENESIS_OUTPUT_PREFIX + txOutput.getIndex();
                    burningManCandidatesByName.putIfAbsent(name, new BurningManCandidate());
                    BurningManCandidate candidate = burningManCandidatesByName.get(name);

                    // Issuance
                    int issuanceHeight = txOutput.getBlockHeight();
                    long issuanceAmount = txOutput.getValue();
                    long decayedAmount = getDecayedGenesisOutputAmount(issuanceAmount);
                    long issuanceDate = daoStateService.getBlockTime(issuanceHeight);
                    candidate.addCompensationModel(new CompensationModel(txOutput.getAddress(),
                            issuanceAmount,
                            decayedAmount,
                            issuanceHeight,
                            txOutput.getTxId(),
                            issuanceDate,
                            0));
                    addBurnOutputModel(chainHeight, proofOfBurnOpReturnTxOutputByHash, name, candidate);
                }));

        Collection<BurningManCandidate> burningManCandidates = burningManCandidatesByName.values();
        double totalDecayedCompensationAmounts = burningManCandidates.stream()
                .mapToDouble(BurningManCandidate::getAccumulatedDecayedCompensationAmount)
                .sum();
        double totalDecayedBurnAmounts = burningManCandidates.stream()
                .mapToDouble(BurningManCandidate::getAccumulatedDecayedBurnAmount)
                .sum();
        long burnTarget = getBurnTarget(chainHeight, burningManCandidates);
        burningManCandidates.forEach(candidate ->
                candidate.calculateShare(totalDecayedCompensationAmounts, totalDecayedBurnAmounts, burnTarget));
        return burningManCandidatesByName;
    }

    private Stream<CompensationProposal> getCompensationProposalsForIssuance(Issuance issuance) {
        return proposalService.getProposalPayloads().stream()
                .map(ProposalPayload::getProposal)
                .filter(proposal -> issuance.getTxId().equals(proposal.getTxId()))
                .filter(proposal -> proposal instanceof CompensationProposal)
                .map(proposal -> (CompensationProposal) proposal);
    }

    private Map<P2PDataStorage.ByteArray, Set<TxOutput>> getProofOfBurnOpReturnTxOutputByHash(int chainHeight) {
        Map<P2PDataStorage.ByteArray, Set<TxOutput>> map = new HashMap<>();
        daoStateService.getProofOfBurnOpReturnTxOutputs().stream()
                .filter(txOutput -> txOutput.getBlockHeight() <= chainHeight)
                .forEach(txOutput -> {
                    P2PDataStorage.ByteArray key = new P2PDataStorage.ByteArray(ProofOfBurnConsensus.getHashFromOpReturnData(txOutput.getOpReturnData()));
                    map.putIfAbsent(key, new HashSet<>());
                    map.get(key).add(txOutput);
                });
        return map;
    }

    private void addBurnOutputModel(int chainHeight,
                                    Map<P2PDataStorage.ByteArray, Set<TxOutput>> proofOfBurnOpReturnTxOutputByHash,
                                    String name,
                                    BurningManCandidate candidate) {
        getProofOfBurnOpReturnTxOutputSetForName(proofOfBurnOpReturnTxOutputByHash, name)
                .forEach(burnOutput -> {
                    int burnOutputHeight = burnOutput.getBlockHeight();
                    Optional<Tx> optionalTx = daoStateService.getTx(burnOutput.getTxId());
                    long burnOutputAmount = optionalTx.map(Tx::getBurntBsq).orElse(0L);
                    long decayedBurnOutputAmount = getDecayedBurnedAmount(burnOutputAmount, burnOutputHeight, chainHeight);
                    long date = optionalTx.map(BaseTx::getTime).orElse(0L);
                    int cycleIndex = getCycleIndex(burnOutputHeight);
                    candidate.addBurnOutputModel(new BurnOutputModel(burnOutputAmount,
                            decayedBurnOutputAmount,
                            burnOutputHeight,
                            burnOutput.getTxId(),
                            date,
                            cycleIndex));
                });
    }

    private static Set<TxOutput> getProofOfBurnOpReturnTxOutputSetForName(Map<P2PDataStorage.ByteArray, Set<TxOutput>> proofOfBurnOpReturnTxOutputByHash,
                                                                          String name) {
        byte[] preImage = name.getBytes(Charsets.UTF_8);
        byte[] hash = ProofOfBurnConsensus.getHash(preImage);
        P2PDataStorage.ByteArray key = new P2PDataStorage.ByteArray(hash);
        if (proofOfBurnOpReturnTxOutputByHash.containsKey(key)) {
            return proofOfBurnOpReturnTxOutputByHash.get(key);
        } else {
            return new HashSet<>();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Burn target
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Burn target using current chain height
    public long getCurrentBurnTarget() {
        // cached value is only used for currentChainHeight
        if (burnTarget.isPresent()) {
            return burnTarget.get();
        }

        burnTarget = Optional.of(getBurnTarget(currentChainHeight, getCurrentBurningManCandidatesByName().values()));
        return burnTarget.get();
    }

    // Burn target using given chain height. Can be historical state.
    public long getBurnTarget(int chainHeight, Collection<BurningManCandidate> burningManCandidates) {
        Optional<Cycle> optionalCycle = daoStateService.getCycle(chainHeight);
        if (optionalCycle.isEmpty()) {
            return 0;
        }

        Cycle currentCycle = optionalCycle.get();
        int firstBlockOfOldestCycle = daoStateService.getPastCycle(currentCycle, NUM_REIMBURSEMENT_CYCLES)
                .map(Cycle::getHeightOfFirstBlock)
                .orElse(daoStateService.getGenesisBlockHeight());

        // Reimbursements
        long accumulatedReimbursements = getReimbursements(chainHeight).stream()
                .filter(reimbursementModel -> reimbursementModel.getHeight() >= firstBlockOfOldestCycle)
                .mapToLong(ReimbursementModel::getAmount)
                .sum();

        // BTC fee revenue
        long accumulatedEstimatedBtcTradeFees = 0;
        Cycle candidateCycle = currentCycle;
        for (int i = 0; i < NUM_REIMBURSEMENT_CYCLES; i++) {
            //  LOCK_TIME_TRADE_PAYOUT was never used. We re-purpose it as value for BTC fee revenue per cycle. This can be added as oracle data by DAO voting.
            // We cannot change the ParamType to BSQ as that would break consensus
            long paramValueAsBlock = daoStateService.getParamValueAsBlock(Param.LOCK_TIME_TRADE_PAYOUT, candidateCycle.getHeightOfFirstBlock());
            // Ignore default value (4320)
            long estimatedBtcTradeFeesPerCycle = paramValueAsBlock != 4320 ? paramValueAsBlock : DEFAULT_EXPECTED_BTC_FEES;
            accumulatedEstimatedBtcTradeFees += estimatedBtcTradeFeesPerCycle;
            Optional<Cycle> previousCycle = daoStateService.getPreviousCycle(candidateCycle);
            if (previousCycle.isPresent()) {
                candidateCycle = previousCycle.get();
            } else {
                break;
            }
        }

        // Burningman
        // Legacy burningman use those opReturn data to mark their burn transactions from delayed payout transaction cases.
        // opReturn data from delayed payout txs when BM traded with the refund agent: 1701e47e5d8030f444c182b5e243871ebbaeadb5e82f
        // opReturn data from delayed payout txs when BM traded with traders who got reimbursed by the DAO: 1701293c488822f98e70e047012f46f5f1647f37deb7
        Set<Tx> proofOfBurnTxs = daoStateService.getProofOfBurnTxs().stream()
                .filter(tx -> tx.getBlockHeight() <= chainHeight)
                .filter(tx -> tx.getBlockHeight() >= firstBlockOfOldestCycle)
                .collect(Collectors.toSet());
        long burnedAmountFromLegacyBurningManDPT = proofOfBurnTxs.stream()
                .filter(e -> {
                    String hash = Hex.encode(e.getLastTxOutput().getOpReturnData());
                    return "1701e47e5d8030f444c182b5e243871ebbaeadb5e82f".equals(hash) ||
                            "1701293c488822f98e70e047012f46f5f1647f37deb7".equals(hash);
                })
                .mapToLong(Tx::getBurntBsq)
                .sum();

        // Legacy burningman use those opReturn data to mark their burn transactions from delayed payout transaction cases.
        // opReturn data from received BTC fees: 1701721206fe6b40777763de1c741f4fd2706d94775d
        long burnedAmountFromLegacyBurningMansBtcFees = proofOfBurnTxs.stream()
                .filter(e -> "1701721206fe6b40777763de1c741f4fd2706d94775d".equals(Hex.encode(e.getLastTxOutput().getOpReturnData())))
                .mapToLong(Tx::getBurntBsq)
                .sum();

        // Distributed BurningMen
        // burningManCandidates are already filtered with chainHeight
        long burnedAmountFromBurningMen = burningManCandidates.stream()
                .map(burningManCandidate -> burningManCandidate.getBurnOutputModels().stream()
                        .filter(burnOutputModel -> burnOutputModel.getHeight() >= firstBlockOfOldestCycle)
                        .mapToLong(BurnOutputModel::getAmount)
                        .sum())
                .mapToLong(e -> e)
                .sum();

        return accumulatedReimbursements
                + accumulatedEstimatedBtcTradeFees
                - burnedAmountFromLegacyBurningManDPT
                - burnedAmountFromLegacyBurningMansBtcFees
                - burnedAmountFromBurningMen;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Reimbursements
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Set<ReimbursementModel> getCurrentReimbursements() {
        // cached value is only used for currentChainHeight
        if (!reimbursements.isEmpty()) {
            return reimbursements;
        }

        Set<ReimbursementModel> set = getReimbursements(currentChainHeight);
        reimbursements.addAll(set);
        return reimbursements;
    }

    private Set<ReimbursementModel> getReimbursements(int chainHeight) {
        Set<ReimbursementModel> reimbursements = new HashSet<>();
        daoStateService.getIssuanceSetForType(IssuanceType.REIMBURSEMENT).stream()
                .filter(issuance -> issuance.getChainHeight() <= chainHeight)
                .forEach(issuance -> getReimbursementProposalsForIssuance(issuance)
                        .forEach(reimbursementProposal -> {
                            int issuanceHeight = issuance.getChainHeight();
                            long issuanceAmount = issuance.getAmount();
                            long issuanceDate = daoStateService.getBlockTime(issuanceHeight);
                            int cycleIndex = getCycleIndex(issuanceHeight);
                            reimbursements.add(new ReimbursementModel(
                                    issuanceAmount,
                                    issuanceHeight,
                                    issuanceDate,
                                    cycleIndex));
                        }));


        return reimbursements;
    }

    private Stream<ReimbursementProposal> getReimbursementProposalsForIssuance(Issuance issuance) {
        return proposalService.getProposalPayloads().stream()
                .map(ProposalPayload::getProposal)
                .filter(proposal -> issuance.getTxId().equals(proposal.getTxId()))
                .filter(proposal -> proposal instanceof ReimbursementProposal)
                .map(proposal -> (ReimbursementProposal) proposal);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Contributor names
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Optional<Set<String>> findMyGenesisOutputNames() {
        return daoStateService.getGenesisTx()
                .flatMap(tx -> Optional.ofNullable(bsqWalletService.getTransaction(tx.getId()))
                        .map(genesisTransaction -> genesisTransaction.getOutputs().stream()
                                .filter(transactionOutput -> transactionOutput.isMine(bsqWalletService.getWallet()))
                                .map(transactionOutput -> BurningManService.GENESIS_OUTPUT_PREFIX + transactionOutput.getIndex())
                                .collect(Collectors.toSet())
                        )
                );
    }

    public Set<String> getMyCompensationRequestNames() {
        return myProposalListService.getList().stream()
                .filter(proposal -> proposal instanceof CompensationProposal)
                .map(Proposal::getName)
                .collect(Collectors.toSet());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Legacy BurningMan
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getBurningManAddressFromParam(int chainHeight) {
        return daoStateService.getParamValue(Param.RECIPIENT_BTC_ADDRESS, chainHeight);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BTC Trade fees
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getBtcFeeReceiverAddress() {
        Map<String, BurningManCandidate> burningManCandidatesByName = getCurrentBurningManCandidatesByName();
        if (burningManCandidatesByName.isEmpty()) {
            // If there are no compensation requests (e.g. at dev testing) we fall back to the default address
            return getBurningManAddressFromParam(currentChainHeight);
        }

        // It might be that we do not reach 100% if some entries had a capped effectiveBurnOutputShare.
        // We ignore that here as there is no risk for abuse. Each entry in the group would have a higher chance in
        // that case.
        // effectiveBurnOutputShare is a % value represented as double. Smallest supported value is 0.01% -> 0.0001.
        // By multiplying it with 10000 and using Math.floor we limit the candidate to 0.01%.
        List<BurningManCandidate> burningManCandidates = new ArrayList<>(burningManCandidatesByName.values());
        List<Long> amountList = burningManCandidates.stream()
                .map(BurningManCandidate::getEffectiveBurnOutputShare)
                .map(effectiveBurnOutputShare -> (long) Math.floor(effectiveBurnOutputShare * 10000))
                .filter(value -> value > 0)
                .collect(Collectors.toList());
        if (amountList.isEmpty()) {
            return getBurningManAddressFromParam(currentChainHeight);
        }
        int winnerIndex = getRandomIndex(amountList, new Random());
        return burningManCandidates.get(winnerIndex).getMostRecentAddress()
                .orElse(getBurningManAddressFromParam(currentChainHeight));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Delayed payout transactions
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We use a snapshot blockHeight to avoid failed trades in case maker and taker have different block heights.
    // The selection is deterministic based on DAO data.
    // The block height is the last mod(10) height from the range of the last 10-20 blocks (139 -> 120; 140 -> 130, 141 -> 130).
    // We do not have the latest dao state by that but can ensure maker and taker have the same block.
    public int getBurningManSelectionHeight() {
        return getSnapshotHeight(daoStateService.getGenesisBlockHeight(), currentChainHeight, 10);
    }

    public List<Tuple2<Long, String>> getDelayedPayoutTxReceivers(int burningManSelectionHeight,
                                                                  long inputAmount,
                                                                  long tradeTxFee) {
        Collection<BurningManCandidate> burningManCandidates = getBurningManCandidatesByName(burningManSelectionHeight).values();
        if (burningManCandidates.isEmpty()) {
            // If there are no compensation requests (e.g. at dev testing) we fall back to the legacy BM
            return List.of(new Tuple2<>(inputAmount, getBurningManAddressFromParam(burningManSelectionHeight)));
        }

        // We need to use the same txFeePerVbyte value for both traders.
        // We use the tradeTxFee value which is calculated from the average of taker fee tx size and deposit tx size.
        // Otherwise, we would need to sync the fee rate of both traders.
        // In case of very large taker fee tx we would get a too high fee, but as fee rate is anyway rather
        // arbitrary and volatile we are on the safer side. The delayed payout tx is published long after the
        // take offer event and the recommended fee at that moment might be very different to actual
        // recommended fee. To avoid that the delayed payout tx would get stuck due too low fees we use a
        // min. fee rate of 10 sat/vByte.

        // Deposit tx has a clearly defined structure, so we know the size. It is only one optional output if range amount offer was taken.
        // Smallest tx size is 246. With additional change output we add 32. To be safe we use the largest expected size.
        double txSize = 278;
        long txFeePerVbyte = Math.max(10, Math.round(tradeTxFee / txSize));
        long spendableAmount = getSpendableAmount(burningManCandidates.size(), inputAmount, txFeePerVbyte);
        // We only use outputs > 500 sat or at least 2 times the cost for the output (32 bytes).
        // If we remove outputs it will be spent as miner fee.
        long minOutputAmount = Math.max(500, txFeePerVbyte * 32 * 2);
        List<Tuple2<Long, String>> receivers = burningManCandidates.stream()
                .filter(candidate -> candidate.getMostRecentAddress().isPresent())
                .map(candidates -> new Tuple2<>(Math.round(candidates.getEffectiveBurnOutputShare() * spendableAmount),
                        candidates.getMostRecentAddress().get()))
                .filter(tuple -> tuple.first >= minOutputAmount)
                .sorted(Comparator.comparing(tuple -> tuple.first))
                .collect(Collectors.toList());
        long totalOutputValue = receivers.stream().mapToLong(e -> e.first).sum();
        if (totalOutputValue < spendableAmount) {
            long available = spendableAmount - totalOutputValue;
            // If the available is larger than 100000 sat (about 20 USD) we send it to legacy BM
            // Otherwise we use it as miner fee
            if (available > 100000) {
                receivers.add(new Tuple2<>(available, getBurningManAddressFromParam(burningManSelectionHeight)));
            }
        }
        return receivers;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private int getCycleIndex(int height) {
        return daoStateService.getCycle(height).map(cycleService::getCycleIndex).orElse(0);
    }

    private static long getSpendableAmount(int numOutputs, long inputAmount, long txFeePerVbyte) {
        // Output size: 32 bytes
        // Tx size without outputs: 51 bytes
        int txSize = 51 + numOutputs * 32;
        long minerFee = txFeePerVbyte * txSize;
        return inputAmount - minerFee;
    }

    private long getDecayedCompensationAmount(long amount, int issuanceHeight, int chainHeight) {
        return getDecayedAmount(amount, issuanceHeight, chainHeight, chainHeight - MAX_COMP_REQUEST_AGE, 0);
    }

    private long getDecayedGenesisOutputAmount(long amount) {
        return Math.round(amount * GENESIS_OUTPUT_AMOUNT_FACTOR);
    }

    private long getDecayedBurnedAmount(long amount, int issuanceHeight, int chainHeight) {
        return getDecayedAmount(amount,
                issuanceHeight,
                chainHeight,
                chainHeight - MAX_BURN_AMOUNT_AGE,
                0);
    }

    // Linear decay between currentBlockHeight (100% of amount) and issuanceHeight (firstBlockOffset % of amount)
    // Values below firstBlockHeight will use the firstBlockOffset as factor for the amount.
    // E.g. if firstBlockOffset is 0.1 the decay goes to 10% and earlier values stay at 10%.
    @VisibleForTesting
    static long getDecayedAmount(long amount,
                                 int issuanceHeight,
                                 int currentBlockHeight,
                                 int firstBlockHeight,
                                 double firstBlockOffset) {
        if (issuanceHeight > currentBlockHeight)
            throw new IllegalArgumentException("issuanceHeight must not be larger than currentBlockHeight. issuanceHeight=" + issuanceHeight + "; currentBlockHeight=" + currentBlockHeight);
        if (currentBlockHeight < 0)
            throw new IllegalArgumentException("currentBlockHeight must not be negative. currentBlockHeight=" + currentBlockHeight);
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative. amount" + amount);
        if (issuanceHeight < 0)
            throw new IllegalArgumentException("issuanceHeight must not be negative. issuanceHeight=" + issuanceHeight);

        double factor = Math.max(0, (issuanceHeight - firstBlockHeight) / (double) (currentBlockHeight - firstBlockHeight));
        double factorWithOffset = firstBlockOffset + factor * (1 - firstBlockOffset);
        long weighted = Math.round(amount * factorWithOffset);
        return Math.max(0, weighted);
    }

    @VisibleForTesting
    static int getRandomIndex(List<Long> weights, Random random) {
        long sum = weights.stream().mapToLong(n -> n).sum();
        long target = random.longs(0, sum).findFirst().orElseThrow() + 1;
        return findIndex(weights, target);
    }

    @VisibleForTesting
    static int findIndex(List<Long> weights, long target) {
        int currentRange = 0;
        for (int i = 0; i < weights.size(); i++) {
            currentRange += weights.get(i);
            if (currentRange >= target) {
                return i;
            }
        }
        return 0;
    }

    // Borrowed from DaoStateSnapshotService. We prefer to not reuse to avoid dependency to an unrelated domain.
    @VisibleForTesting
    static int getSnapshotHeight(int genesisHeight, int height, int grid) {
        return Math.round(Math.max(genesisHeight + 3 * grid, height) / grid) * grid - grid;
    }
}
