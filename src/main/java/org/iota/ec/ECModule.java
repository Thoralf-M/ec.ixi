package org.iota.ec;

import org.iota.ict.ec.AutonomousEconomicActor;
import org.iota.ict.ec.EconomicCluster;
import org.iota.ict.ec.TrustedEconomicActor;
import org.iota.ict.eee.EffectListener;
import org.iota.ict.eee.Environment;
import org.iota.ict.ixi.Ixi;
import org.iota.ict.ixi.IxiModule;
import org.iota.ict.ixi.context.IxiContext;
import org.iota.ict.ixi.context.SimpleIxiContext;
import org.iota.ict.model.bundle.Bundle;
import org.iota.ict.model.bundle.BundleBuilder;
import org.iota.ict.model.transaction.Transaction;
import org.iota.ict.model.transfer.InputBuilder;
import org.iota.ict.model.transfer.OutputBuilder;
import org.iota.ict.model.transfer.TransferBuilder;
import org.iota.ict.utils.Trytes;
import org.iota.ict.utils.crypto.AutoIndexedMerkleTree;
import org.iota.ict.utils.crypto.SignatureSchemeImplementation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.*;

public class ECModule extends IxiModule {

    private static final int TIPS_PER_TRANSFER = 1;
    private static final int TRANSFER_SECURITY = 1;
    private static final double CONFIRMATION_CONFIDENCE = 0.95;

    private final API api;
    private final EconomicCluster cluster;
    private final List<AutonomousEconomicActor> autonomousActors = new LinkedList<>();
    private final List<TrustedEconomicActor> trustedActors = new LinkedList<>();
    private final List<String> transfers = new LinkedList<>();
    private final Map<String, BigInteger> initialBalances = new HashMap<>();

    private final IxiContext context = new ECContext();

    public ECModule(Ixi ixi) {
        super(ixi);
        this.cluster = new EconomicCluster(ixi);
        this.api = new API(this);

        transfers.add(Transaction.NULL_TRANSACTION.hash);
        createNewActor(Trytes.randomSequenceOfLength(81), 3, 0);
        AutonomousEconomicActor actor = autonomousActors.get(0);
        considerTangle(actor.getAddress(), Transaction.NULL_TRANSACTION.hash, Transaction.NULL_TRANSACTION.hash);
    }

    /****** IXI ******/

    @Override
    public void run() {
        System.err.println("EC is running!");
    }

    @Override
    public IxiContext getContext() {
        return context;
    }

    @Override
    public void onStart() {
        Persistence.load(this);
    }

    @Override
    public void onTerminate() {
        Persistence.store(this);
    }

    private class ECContext extends SimpleIxiContext {

        public String respondToRequest(String request) {
            return api.processRequest(request);
        }
    }

    /****** SERVICES ******/

    void considerTangle(String actorAddress, String trunk, String branch) {
        AutonomousEconomicActor actor = findAutonomousActor(actorAddress);
        if(actor == null)
            throw new IllegalArgumentException("None of the actors controlled by you has the address '"+actorAddress+"'");
        // TODO validate Tangle
        Bundle bundle = actor.buildMarker(trunk, branch, 0.05); // TODO initial confidence
        for(Transaction transaction : bundle.getTransactions())
            ixi.submit(transaction);
    }

    double getConfidence(String hash) {
        return cluster.determineApprovalConfidence(hash);
    }

    JSONArray getMarkers(String actorAddress) {
        TrustedEconomicActor actor = findTrustedActor(actorAddress);
        if(actor == null)
            throw new IllegalArgumentException("You are not following an actor with the address '"+actorAddress+"'");
        JSONArray markers = new JSONArray();
        for(String tangle : actor.getMarkedTangles()) {
            JSONObject marker = new JSONObject();
            marker.put("ref1", tangle.substring(0, 81));
            marker.put("ref2", tangle.substring(81));
            marker.put("confidence", -1); // TODO
            markers.put(marker);
        }
        return markers;
    }

    double getConfidenceByActor(String hash, String actorAddress) {
        TrustedEconomicActor actor = findTrustedActor(actorAddress);
        return actor == null ? -1 : actor.getConfidence(hash);
    }

    void createNewActor(String seed, int merkleTreeDepth, int startIndex) {
        AutoIndexedMerkleTree merkleTree = new AutoIndexedMerkleTree(seed, 3, merkleTreeDepth, startIndex);
        AutonomousEconomicActor actor = new AutonomousEconomicActor(ixi, cluster, initialBalances, merkleTree);
        autonomousActors.add(actor);
    }

    void deleteAutonomousActor(String address) {
        AutonomousEconomicActor actor = findAutonomousActor(address);
        if(actor == null)
            throw new IllegalArgumentException("You do not own an actor with address '"+address+"'.");
        autonomousActors.remove(actor);
    }

    void setTrust(String address, double trust) {

        TrustedEconomicActor actor;
        if((actor = findTrustedActor(address)) != null) {
            actor.setTrust(trust);
            if(trust == 0) {
                trustedActors.remove(actor);
                // TODO cluster.removeActor(actor);
            }
        } else if(trust > 0) {
            TrustedEconomicActor trustedEconomicActor = new TrustedEconomicActor(address, trust);
            cluster.addActor(trustedEconomicActor);
            trustedActors.add(trustedEconomicActor);
        }
    }

    String sendTransfer(String seed, int index, String receiverAddress, String remainderAddress, BigInteger value, boolean checkBalances) {
        SignatureSchemeImplementation.PrivateKey privateKey = SignatureSchemeImplementation.derivePrivateKeyFromSeed(seed, index, TRANSFER_SECURITY);
        BigInteger balance = getBalanceOfAddress(privateKey.deriveAddress());
        Set<String> tips = findTips(TIPS_PER_TRANSFER);
        TransferBuilder transferBuilder = buildTransfer(privateKey, balance, receiverAddress, remainderAddress, value, tips, checkBalances);
        return submitTransfer(transferBuilder);
    }

    BigInteger getBalanceOfAddress(String address) {
        BigInteger sum = initialBalances.getOrDefault(address, BigInteger.ZERO);
        Set<Transaction> transactionsOnAddress = ixi.findTransactionsByAddress(address);
        for(Transaction transaction : transactionsOnAddress) {
            if(!transaction.value.equals(BigInteger.ZERO)) {
                double confidence = cluster.determineApprovalConfidence(transaction.hash);
                if(confidence > CONFIRMATION_CONFIDENCE)
                    sum = sum.add(transaction.value);
            }
        }
        return sum;
    }

    Bundle getBundle(String bundleHead) {
        return new Bundle(ixi.findTransactionByHash(bundleHead));
    }

    /****** HELPERS ******/

    private AutonomousEconomicActor findAutonomousActor(String address) {
        for(AutonomousEconomicActor actor : autonomousActors) {
            if(actor.getAddress().equals(address))
                return actor;
        }
        return null;
    }

    private TrustedEconomicActor findTrustedActor(String address) {
        for(TrustedEconomicActor actor : trustedActors) {
            if(actor.getAddress().equals(address))
                return actor;
        }
        return null;
    }

    static List<String> deriveAddressesFromSeed(String seed, int amount) {
        List<String> addresses = new LinkedList<>();
        for(int i = 0; i < amount; i++)
            addresses.add(SignatureSchemeImplementation.deriveAddressFromSeed(seed, i, TRANSFER_SECURITY));
        return addresses;
    }

    private TransferBuilder buildTransfer(SignatureSchemeImplementation.PrivateKey privateKey, BigInteger balance, String receiverAddress, String remainderAddress, BigInteger value, Set<String> references, boolean checkBalances) {
        if(balance.compareTo(value) < 0 && checkBalances)
            throw new IllegalArgumentException("insufficient balance (balance="+balance+" < value="+value+")");
        InputBuilder inputBuilder = new InputBuilder(privateKey, BigInteger.ZERO.subtract(balance));
        Set<OutputBuilder> outputs = new HashSet<>();
        outputs.add(new OutputBuilder(receiverAddress, value, "EC9RECEIVER"));
        if(!balance.equals(value))
            outputs.add(new OutputBuilder(remainderAddress, balance.subtract(value), "EC9REMAINDER"));
        return new TransferBuilder(Collections.singleton(inputBuilder), outputs, TRANSFER_SECURITY);
    }

    private Set<String> findTips(int amount) {
        Set<String> tips = new HashSet<>();
        for(int i = 0; i < amount; i++)
            tips.add(findTip());
        return tips;
    }

    private String findTip() {
        return Transaction.NULL_TRANSACTION.hash;
        //throw new RuntimeException("implement me");
    }

    /**
     * Builds a transfer and submits it to the Ict network.
     * @param transferBuilder Modeled transfer to submit.
     * @return Hash of bundle head of submitted bundle.
     * */
    private String submitTransfer(TransferBuilder transferBuilder) {
        BundleBuilder bundleBuilder = transferBuilder.build();
        Bundle bundle = bundleBuilder.build();
        for(Transaction transaction : bundle.getTransactions())
            ixi.submit(transaction);
        String hash = bundle.getHead().hash;
        transfers.add(hash);
        return hash;
    }

    public void changeInitialBalance(String address, BigInteger change) {
        initialBalances.put(address, initialBalances.getOrDefault(address, BigInteger.ZERO).add(change));
        for(AutonomousEconomicActor actor : autonomousActors) {
            // TODO update actor
        }
    }

    /****** GETTERS *****/

    List<TrustedEconomicActor> getTrustedActors() {
        return new LinkedList<>(trustedActors);
    }

    List<AutonomousEconomicActor> getAutonomousActors() {
        return new LinkedList<>(autonomousActors);
    }

    public List<String> getTransfers() {
        return new LinkedList<>(transfers);
    }
}