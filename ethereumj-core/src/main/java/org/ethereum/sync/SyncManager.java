package org.ethereum.sync;

import org.ethereum.config.SystemProperties;
import org.ethereum.core.*;
import org.ethereum.listener.CompositeEthereumListener;
import org.ethereum.listener.EthereumListener;
import org.ethereum.net.eth.handler.Eth62;
import org.ethereum.net.server.Channel;
import org.ethereum.net.server.ChannelManager;
import org.ethereum.util.ExecutorPipeline;
import org.ethereum.util.Functional;
import org.ethereum.validator.BlockHeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static java.util.Collections.singletonList;
import static org.ethereum.core.ImportResult.*;

/**
 * @author Mikhail Kalinin
 * @since 14.07.2015
 */
@Component
public class SyncManager {

    private final static Logger logger = LoggerFactory.getLogger("sync");


    private static final int BLOCK_QUEUE_LIMIT = 20000;
    private static final int HEADER_QUEUE_LIMIT = 20000;

    // Transaction.getSender() is quite heavy operation so we are prefetching this value on several threads
    // to unload the main block importing cycle
    private ExecutorPipeline<BlockWrapper,BlockWrapper> exec1 = new ExecutorPipeline<>
            (4, 1000, true, new Functional.Function<BlockWrapper,BlockWrapper>() {
                public BlockWrapper apply(BlockWrapper blockWrapper) {
                    for (Transaction tx : blockWrapper.getBlock().getTransactionsList()) {
                        tx.getSender();
                    }
                    return blockWrapper;
                }
            }, new Functional.Consumer<Throwable>() {
                public void accept(Throwable throwable) {
                    logger.error("Unexpected exception: ", throwable);
                }
            });

    private ExecutorPipeline<BlockWrapper, Void> exec2 = exec1.add(1, 1, new Functional.Consumer<BlockWrapper>() {
        @Override
        public void accept(BlockWrapper blockWrapper) {
            blockQueue.add(blockWrapper);
        }
    });

    /**
     * Queue with validated blocks to be added to the blockchain
     */
    private BlockingQueue<BlockWrapper> blockQueue = new LinkedBlockingQueue<>();

    private boolean syncDone = false;

    @Autowired
    private Blockchain blockchain;

    @Autowired
    private BlockHeaderValidator headerValidator;

    @Autowired
    private CompositeEthereumListener compositeEthereumListener;

    @Autowired
    SyncPool pool;

    @Autowired
    SystemProperties config;

    @Autowired
    EthereumListener ethereumListener;

    @Autowired
    ChannelManager channelManager;

    private SyncQueueIfc syncQueueNew;

    private CountDownLatch receivedHeadersLatch = new CountDownLatch(0);
    private CountDownLatch receivedBlocksLatch = new CountDownLatch(0);

    @PostConstruct
    public void init() {

        // make it asynchronously
        new Thread(new Runnable() {
            @Override
            public void run() {

                if (!config.isSyncEnabled()) {
                    logger.info("Sync Manager: OFF");
                    return;
                }

                logger.info("Sync Manager: ON");

                Runnable queueProducer = new Runnable(){

                    @Override
                    public void run() {
                        produceQueue();
                    }
                };

                Thread t=new Thread (queueProducer, "SyncQueueThread");
                t.start();

                try {
                    Thread.sleep(5000); // TODO !!!!!!!!!!!!!!!!!!!!!! blockchain is not initialized here
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                syncQueueNew = new SyncQueueImpl(blockchain);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        headerRetrieveLoop();
                    }
                }, "NewSyncThreadHeaders").start();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        blockRetrieveLoop();
                    }
                }, "NewSyncThreadBlocks").start();

                if (logger.isInfoEnabled()) {
                    startLogWorker();
                }

            }
        }).start();
    }

    private void headerRetrieveLoop() {
        while(true) {
            try {

                if (syncQueueNew.getHeadersCount() < HEADER_QUEUE_LIMIT) {
                    Channel any = pool.getAnyIdle();

                    if (any != null) {
                        Eth62 eth = (Eth62) any.getEthHandler();

                        SyncQueueIfc.HeadersRequest hReq = syncQueueNew.requestHeaders();
                        eth.sendGetBlockHeaders(hReq.getStart(), hReq.getCount(), hReq.isReverse());
                    }
                }
                receivedHeadersLatch = new CountDownLatch(1);
                receivedHeadersLatch.await(2000, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                logger.error("Unexpected: ", e);
            }
        }
    }

    private void blockRetrieveLoop() {
        while(true) {
            try {

                if (blockQueue.size() < BLOCK_QUEUE_LIMIT) {
                    SyncQueueIfc.BlocksRequest bReq = syncQueueNew.requestBlocks(1000);
                    int reqBlocksCounter = 0;
                    for (SyncQueueIfc.BlocksRequest blocksRequest : bReq.split(100)) {
                        Channel any = pool.getAnyIdle();
                        if (any == null) break;
                        Eth62 eth = (Eth62) any.getEthHandler();
                        eth.sendGetBlockBodies(blocksRequest.getBlockHeaders());
                        reqBlocksCounter ++;
                    }
                    receivedBlocksLatch = new CountDownLatch(reqBlocksCounter);
                } else {
                    receivedBlocksLatch = new CountDownLatch(1);
                }

                receivedBlocksLatch.await(2000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.error("Unexpected: ", e);
            }
        }
    }

    /**
     * Processing the queue adding blocks to the chain.
     */
    private void produceQueue() {

        while (true) {

            BlockWrapper wrapper = null;
            try {

                wrapper = blockQueue.take();

                logger.debug("BlockQueue size: {}, headers queue size: {}", blockQueue.size(), syncQueueNew.getHeadersCount());
                ImportResult importResult = blockchain.tryToConnect(wrapper.getBlock());

                if (importResult == IMPORTED_BEST) {
                    logger.info("Success importing BEST: block.number: {}, block.hash: {}, tx.size: {} ",
                            wrapper.getNumber(), wrapper.getBlock().getShortHash(),
                            wrapper.getBlock().getTransactionsList().size());

                    if (wrapper.isNewBlock() && !syncDone) {
                        syncDone = true;
                        EventDispatchThread.invokeLater(new Runnable() {
                            public void run() {
                                compositeEthereumListener.onSyncDone();
                            }
                        });
                    }
                }

                if (importResult == IMPORTED_NOT_BEST)
                    logger.info("Success importing NOT_BEST: block.number: {}, block.hash: {}, tx.size: {} ",
                            wrapper.getNumber(), wrapper.getBlock().getShortHash(),
                            wrapper.getBlock().getTransactionsList().size());

                if (syncDone && (importResult == IMPORTED_BEST || importResult == IMPORTED_NOT_BEST)) {
                    if (logger.isDebugEnabled()) logger.debug("Block dump: " + Hex.toHexString(wrapper.getBlock().getEncoded()));
                }

                // In case we don't have a parent on the chain
                // return the try and wait for more blocks to come.
                if (importResult == NO_PARENT) {
                    logger.error("No parent on the chain for block.number: {} block.hash: {}",
                            wrapper.getNumber(), wrapper.getBlock().getShortHash());
                }

            } catch (Throwable e) {
                logger.error("Error processing block {}: ", wrapper.getBlock().getShortDescr(), e);
                logger.error("Block dump: {}", Hex.toHexString(wrapper.getBlock().getEncoded()));
            }

        }
    }

    /**
     * Adds a list of blocks to the queue
     *
     * @param blocks block list received from remote peer and be added to the queue
     * @param nodeId nodeId of remote peer which these blocks are received from
     */
    public void addList(List<Block> blocks, byte[] nodeId) {

        if (blocks.isEmpty()) {
            return;
        }

        List<Block> newBlocks = syncQueueNew.addBlocks(blocks);

        List<BlockWrapper> wrappers = new ArrayList<>();
        for (Block b : newBlocks) {
            wrappers.add(new BlockWrapper(b, nodeId));
        }

        exec1.pushAll(wrappers);

        receivedBlocksLatch.countDown();

        if (logger.isDebugEnabled()) logger.debug(
                "Blocks waiting to be proceed:  queue.size: [{}] lastBlock.number: [{}]",
                blockQueue.size(),
                blocks.get(blocks.size() - 1).getNumber()
        );
    }

    /**
     * Adds NEW block to the queue
     *
     * @param block new block
     * @param nodeId nodeId of the remote peer which this block is received from
     *
     * @return true if block passed validations and was added to the queue,
     *         otherwise it returns false
     */
    public boolean validateAndAddNewBlock(Block block, byte[] nodeId) {

        // run basic checks
        if (!isValid(block.getHeader())) {
            return false;
        }

        syncQueueNew.addHeaders(singletonList(new BlockHeaderWrapper(block.getHeader(), nodeId)));
        List<Block> newBlocks = syncQueueNew.addBlocks(singletonList(block));

        List<BlockWrapper> wrappers = new ArrayList<>();
        for (Block b : newBlocks) {
            boolean newBlock = Arrays.equals(block.getHash(), b.getHash());
            BlockWrapper wrapper = new BlockWrapper(b, newBlock, nodeId);
            wrapper.setReceivedAt(System.currentTimeMillis());
            wrappers.add(wrapper);
        }

        exec1.pushAll(wrappers);

        logger.debug("Blocks waiting to be proceed:  queue.size: [{}] lastBlock.number: [{}]",
                blockQueue.size(),
                block.getNumber());

        return true;
    }

    /**
     * Adds list of headers received from remote host <br>
     * Runs header validation before addition <br>
     * It also won't add headers of those blocks which are already presented in the queue
     *
     * @param headers list of headers got from remote host
     * @param nodeId remote host nodeId
     *
     * @return true if blocks passed validation and were added to the queue,
     *          otherwise it returns false
     */
    public boolean validateAndAddHeaders(List<BlockHeader> headers, byte[] nodeId) {

        if (headers.isEmpty()) return true;

        List<BlockHeaderWrapper> wrappers = new ArrayList<>(headers.size());

        for (BlockHeader header : headers) {

            if (!isValid(header)) {

                if (logger.isDebugEnabled()) {
                    logger.debug("Invalid header RLP: {}", Hex.toHexString(header.getEncoded()));
                }

                return false;
            }

            wrappers.add(new BlockHeaderWrapper(header, nodeId));
        }

        syncQueueNew.addHeaders(wrappers);

        receivedHeadersLatch.countDown();

        logger.debug("{} headers added", headers.size());

        return true;
    }

    /**
     * Runs checks against block's header. <br>
     * All these checks make sense before block is added to queue
     * in front of checks running by {@link BlockchainImpl#isValid(BlockHeader)}
     *
     * @param header block header
     * @return true if block is valid, false otherwise
     */
    private boolean isValid(BlockHeader header) {

        if (!headerValidator.validate(header)) {

            headerValidator.logErrors(logger);
            return false;
        }

        return true;
    }
    public boolean isSyncDone() {
        return syncDone;
    }

    private void startLogWorker() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    pool.logActivePeers();
                    logger.info("\n");
                } catch (Throwable t) {
                    t.printStackTrace();
                    logger.error("Exception in log worker", t);
                }
            }
        }, 0, 30, TimeUnit.SECONDS);
    }
}
