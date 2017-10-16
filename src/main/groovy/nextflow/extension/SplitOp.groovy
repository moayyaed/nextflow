package nextflow.extension
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.splitter.AbstractSplitter
import nextflow.splitter.FastqSplitter
import nextflow.splitter.SplitterFactory
/**
 * Implements splitter operators:
 * - splitText
 * - splitCsv
 * - splitFasta
 * - splitFastq
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class SplitOp {

    /**
     * The channel to which this operator is applied
     */
    private DataflowReadChannel source

    /**
     * Index of the elements to which a split operation need to be applied
     */
    private List<Integer> indexes

    /**
     * The name of the operator eg. {code splitFasta}
     */
    private String methodName

    /**
     * Operator named parameters
     */
    private Map params

    /**
     * Whenever the splitter is applied to a paired-end read files (only valid for {@code splitFastaq} operator.
     */
    private boolean pairedEnd

    private boolean multiSplit

    /**
     * Creates a splitter operator
     *
     * @param source The source channel to which apply to operator
     * @param methodName The operator method name eg. {@code splitFasta}, {@code splitCsv}, etc.
     * @param opts The operator named options
     */
    SplitOp( DataflowReadChannel source, String methodName, Map opts ) {

        this.source = source
        this.params = opts != null ? new HashMap(opts) : new HashMap<>()
        this.methodName = methodName

        if( params.pe && methodName != 'splitFastq' )
            throw new IllegalArgumentException("Unknown argument 'pe' for operator 'splitFastq'")

        if( params.pe==true && params.elem )
            throw new IllegalArgumentException("Parameter `pe` and `elem` conflicts")

        if( params.pe == true ) {
            indexes = [-1,-2]
            multiSplit = true
            pairedEnd = true
        }
        if( params.elem instanceof List<Integer> ) {
            indexes = params.elem as List<Integer>
            multiSplit = true
        }

        // -- validate options
        if( params.containsKey('autoClose') )
            throw new IllegalArgumentException('Parameter `autoClose` do not supported')
        // turn off channel auto-close
        params.autoClose = false

        if( params.into && !(params.into instanceof DataflowQueue) )
            throw new IllegalArgumentException('Parameter `into` must reference a channel object')

    }

    /** Only for testing -- do not use */
    @PackageScope SplitOp() { }

    @PackageScope String getMethodName() { methodName }

    @PackageScope boolean isMultiSplit() { multiSplit }

    @PackageScope boolean isPairedEnd() { pairedEnd }

    @PackageScope List<Integer> getIndexes() { indexes }

    @PackageScope getParams() { params }

    DataflowWriteChannel apply() {
        multiSplit ? splitMultiEntries() : splitSingleEntry(source, params)
    }

    /**
     * Split more than one elements. Each split operation is handled
     * on a separate channel. All channels are then merged to a
     * single output result channel.
     */
    protected DataflowWriteChannel splitMultiEntries() {

        final cardinality = indexes.size()

        // creates a copy of `source` channel for each element to split
        def copies = new IntoOp(source, cardinality).apply().getOutputs()

        // applies the splitter the each channel copy
        def splitted = new ArrayList(cardinality)
        for( int i=0; i<cardinality; i++ ) {
            def channel = (DataflowQueue)copies.get(i)
            def opts = new HashMap(params)
            opts.remove('pe')
            opts.elem = indexes.get(i)
            def result = splitSingleEntry(channel, opts)
            splitted.add( result )
        }

        // now merge the result
        def output = new DataflowQueue()
        applyMergingOperator(splitted, output, indexes)
        return output
    }

    /**
     * Apply the split operation to a single element
     */
    protected DataflowWriteChannel splitSingleEntry(DataflowReadChannel origin, Map params) {

        def output = getOrCreateDataflowQueue(params)

        // create the splitter and set the options
        def splitter = createSplitter(methodName, params)

        if( multiSplit )
            splitter.multiSplit = true

        if( pairedEnd )
            (splitter as FastqSplitter).emitSplitIndex = true

        applySplittingOperator(origin, output, splitter)
        return output
    }

    protected void applySplittingOperator( DataflowReadChannel origin, DataflowWriteChannel output, AbstractSplitter splitter ) {
        final next = { entry -> splitter.target(entry).apply() }
        final done = { output << Channel.STOP }

        DataflowHelper.subscribeImpl ( origin, [onNext: next, onComplete: done ])
    }

    protected AbstractSplitter createSplitter(String methodName, Map params) {
        SplitterFactory
                .create(methodName)
                .options(params) as AbstractSplitter
    }

    protected void applyMergingOperator(List splitted, DataflowQueue output, List<Integer> indexes) {
        DataflowHelper.newOperator(splitted, [output], new SplitterMergeClosure(indexes))
    }

    protected DataflowWriteChannel getOrCreateDataflowQueue(Map params) {
        def result
        // create a new DataflowChannel that will receive the splitter entries
        if( params.into instanceof DataflowQueue ) {
            result = (DataflowQueue)params.into
        }
        else {
            result = params.into = new DataflowQueue<>()
        }

        return result
    }
}