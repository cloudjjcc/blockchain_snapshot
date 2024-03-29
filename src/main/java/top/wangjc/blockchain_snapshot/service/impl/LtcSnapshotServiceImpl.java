package top.wangjc.blockchain_snapshot.service.impl;

import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlock;
import top.wangjc.blockchain_snapshot.document.LtcUTXODocument;
import top.wangjc.blockchain_snapshot.document.OperationLogDocument;
import top.wangjc.blockchain_snapshot.dto.LtcBlock;
import top.wangjc.blockchain_snapshot.dto.LtcBlockHash;
import top.wangjc.blockchain_snapshot.repository.MongoRepositoryImpl;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LtcSnapshotServiceImpl extends AbstractSnapshotService {
    public static final String METHOD_GET_BLOCK_HASH = "getblockhash";
    public static final String METHOD_GET_BLOCK = "getblock";
    @Value("${ltc.username:}")
    private String username;
    @Value("${ltc.password:}")
    private String password;
    @Value("${ltc.httpAddr}")
    private String httpAddr;
    @Value("${ltc.batchSize:100}")
    private Integer batchSize;
    @Value("${ltc.startBlock:}")
    private Integer startBlock;
    @Value("${ltc.endBlock:}")
    private Integer endBlock;
    private EnhanceHttpServiceImpl httpService;

    @Autowired
    private MongoRepositoryImpl mongoRepository;

    protected LtcSnapshotServiceImpl() {
        super(ChainType.Litecoin, "litecoin_snapshot");
    }

    @Override
    protected void doInit() {
        super.doInit();
        final String basic = Credentials.basic(username, password);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .authenticator((route, response) -> response.request().newBuilder().header("Authorization", basic).build())
                .connectionPool(new ConnectionPool())
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        httpService = new EnhanceHttpServiceImpl(httpAddr, httpClient);
    }

    @Override
    protected void handleLog(OperationLogDocument operationLog) {

    }

    @Override
    protected OperationLogDocument handleBatchBlock(List<Integer> batch) {
//        log.info("=======start handle {}-{} block ===========", batch.get(0), batch.get(batch.size() - 1));
//        long start = System.currentTimeMillis();
        OperationLogDocument logEntity = new OperationLogDocument(batch.get(0), batch.get(batch.size() - 1), chainType);
        // 账号信息
        List<LtcUTXODocument> documents = new ArrayList<>();
        Flowable.just(batch)
                .map(this::generateBlockHashRequests)
                .map(this::getBatchBlockHashes)
                .map(this::generateBlockRequests)
                .map(this::getBatchBlocks)
                .flatMapIterable(ltcBlocks -> ltcBlocks)
                .subscribe(this::handleBlock, this::handleError, () -> {
//                    blockCounter.increse(batch.size());
//                    log.info("======handle {}-{} batch block complete,cost:{} ms=======", batch.get(0), batch.get(batch.size() - 1), (System.currentTimeMillis() - start));
                });
        return logEntity;
    }

    /**
     * 处理区块信息
     *
     * @param block
     */
    private void handleBlock(LtcBlock block) {
        LtcBlock.Block ltcBlock = block.getBlock();
        List<LtcUTXODocument> utxoDocuments = new ArrayList<>();
        List<String> outPoints = new ArrayList<>();
        ltcBlock.getTransactions().forEach(transaction -> {
//            if (!transaction.isCoinbase()) {
//                transaction.getInputs().forEach(input -> {
//                    String outPoint = LtcUTXODocument.generateOutPoint(input.getTxid(), input.getIndex());
//                    outPoints.add(outPoint);
//                });
//            }
            transaction.getOutputs().forEach(output -> {
                LtcUTXODocument document = new LtcUTXODocument();
//                document.setBlockHash(ltcBlock.getHash());
//                document.setBlockHeight(ltcBlock.getHeight());
                document.setTxId(transaction.getTxid());
                List<String> addresses = output.getScriptPubKey().getAddresses();
                String addressStr = addresses.size() > 0 ? addresses.get(0) : "";
                document.setAddress(addressStr);
                document.setSpentBlock(Strings.EMPTY);
                document.setCoinBase(transaction.isCoinbase());
                document.setMintValue(output.getValue());
                document.setMintIndex(output.getIndex());
                document.setOutPoint(LtcUTXODocument.generateOutPoint(document.getTxId(), document.getMintIndex()));
                utxoDocuments.add(document);
            });
        });
        // 生成utxo
//        TimePrint timePrint=new TimePrint();
        Flowable.fromIterable(utxoDocuments).buffer(1000).subscribe(utxos -> mongoRepository.batchSave(utxos), this::handleError);
//        timePrint.markPoint("1");
        // utxo 被消费
//        mongoRepository.updateUTXOByOutpoint(outPoints, block.getBlock().getHash(),LtcUTXODocument.class);
        increaseCounter();
    }

    /**
     * 生成区块hash请求
     *
     * @param blockNumbers
     * @return
     */
    private List<Request> generateBlockHashRequests(List<Integer> blockNumbers) {
        return blockNumbers.stream().map(blockNum -> new Request<>(
                METHOD_GET_BLOCK_HASH,
                Arrays.asList(blockNum),
                this.httpService,
                EthBlock.class)).collect(Collectors.toList());
    }

    /**
     * 批量获取区块hash
     *
     * @param requests
     * @return
     */
    private List<LtcBlockHash> getBatchBlockHashes(List<Request> requests) throws IOException {
        return httpService.sendBatch(requests, LtcBlockHash.class);
    }

    /**
     * 批量获取区块信息
     *
     * @param requests
     * @return
     */
    private List<LtcBlock> getBatchBlocks(List<Request> requests) throws IOException {
        return httpService.sendBatch(requests, LtcBlock.class);
    }

    /**
     * 生成区块RPC请求信息
     *
     * @param blockHashes
     * @return
     */
    private List<Request> generateBlockRequests(List<LtcBlockHash> blockHashes) {
        return blockHashes.stream().map(blockHash -> new Request<>(
                METHOD_GET_BLOCK,
                Arrays.asList(blockHash.getBlockHash(), 2),
                this.httpService,
                LtcBlock.class)).collect(Collectors.toList());
    }

    @Override
    public int getStartBlock() {
        return startBlock;
    }

    @Override
    public int getEndBlock() {
        return endBlock;
    }

    @Override
    public int getBatchSize() {
        return batchSize;
    }
}
