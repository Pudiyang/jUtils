package uk.ac.ebi.utils.threading.batchproc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.utils.exceptions.UnexpectedEventException;
import uk.ac.ebi.utils.threading.HackedBlockingQueue;


/**  
 * <p>A simple class to manage processing of data in multi-thread mode.</p>
 * 
 * <p>The idea implemented here is that {@link #process(Object, Object...)} runs a loop where: 
 * 
 * <ul>
 *   <li>gets a data item from its input source of type S, for instance S might be a list of records</li>
 *   <li>optionally transforms the data item and sends the result to the current batch collector of type B, for instance
 *   		 B might be of type {@code Set<S>}</li>
 *   <li>invokes {@link #handleNewBatch(Object, boolean)}, which 
 *   {@link #decideNewBatch(Object) decides} if it's time to issue a new {@link #getBatchJob() batch processing job}.
 *   For example, the job might save the records in a single transaction. If a new job is actually issued by 
 *   {@link #handleNewBatch(Object, boolean)}, this also asks {@link #getBatchFactory()} for a new (eg, empty) batch, 
 *   and hence the hereby process() method should assign this new batch to the current batch being filled with items 
 *   from S. For instance, {@link #handleNewBatch(Object, boolean)} might decide to issue a new job when the current 
 *   set of records has enough elements and then {@link #getBatchFactory()} might be used to get a new empty set of 
 *   records.</li>
 * </ul></p>
 *
 * <p>Each invocation of {@link #handleNewBatch(Object, boolean)} might spawn the creation of a new
 * batch job, via {@link #getBatchJob()}, which is then submitted to the {@link #getExecutor() executor service}, 
 * and hence the batch processing happens in multi-thread mode.</p>
 * 
 * <p>After the main loop described above, {@link #process(Object, Object...)} should invoke {@link #waitExecutor(String)},
 * to wait for the completion of the that the parallel jobs execution.</p>
 * 
 * <p>The call to {@link #waitExecutor(String)} also resets the internal {@link ExecutorService}, which will be 
 * recreated (once) upon the first invocation of {@link #getExecutor()}. This behaviour ensures that {@link #process(Object, Object...)}
 * can be invoked multiple times reusing the same batchJob instance (normally that's not possible for 
 * an {@link ExecutorService} after its {@link ExecutorService#awaitTermination(long, TimeUnit)} method is called).</p> 
 * 
 * <p>You can find a usage example of this class in the <a href = "TODO">rdf-utils-jena package</a>.
 *  
 * @author brandizi
 * <dl><dt>Date:</dt><dd>1 Dec 2017</dd></dl>
 *
 */
public abstract class BatchProcessor<B, BC extends BatchCollector<B>, BJ extends Consumer<B>>
{
	private BJ batchJob;
	private BC batchCollector;
	
	private Supplier<ExecutorService> executorFactory = HackedBlockingQueue::createExecutor;
	private ExecutorService executor;
	
	private AtomicLong submittedBatches = new AtomicLong ( 0 );
	private AtomicLong completedBatches = new AtomicLong ( 0 );

	/** @see {@link #wrapBatchJob(Runnable)} */
	protected long jobLogPeriod = 1000;
	
	protected Logger log = LoggerFactory.getLogger ( this.getClass () );
	
		
	public BatchProcessor ( BJ batchJob, BC batchCollector ) {
		super ();
		this.batchJob = batchJob;
		this.batchCollector = batchCollector;
	}
	
	
	public BatchProcessor ( BJ batchJob ) {
		this ( batchJob, null );
	}
	
	public BatchProcessor () {
		this ( null, null );
	}
	
		
	protected B handleNewBatch ( B currentBatch ) {
		return handleNewBatch ( currentBatch, false );
	}

	/**
	 * This is the method that possibly issues a new task, via the {@link #getExecutor()}, which runs 
	 * the {@link #getBatchJob()} against the current batch. 
	 * 
	 * Note that the batch job will be executed under the {@link #wrapBatchJob(Runnable) default wrapper}.  
	 * 
	 * @param forceFlush if true it flushes the data independently of {@link #decideNewBatch(Object)}.
	 * 
	 */
	protected B handleNewBatch ( B currentBatch, boolean forceFlush )
	{		
		BatchCollector<B> bcoll = this.batchCollector;
		if ( !( forceFlush || bcoll.batchReadyFlag ().test ( currentBatch ) ) ) return currentBatch;

		getExecutor ().submit ( wrapBatchJob ( () -> batchJob.accept ( currentBatch ) ) );
		
		if ( this.submittedBatches.incrementAndGet () % this.jobLogPeriod == 0 ) 
			log.info ( "{} batch jobs submitted", this.submittedBatches.get () );
		
		return bcoll.batchFactory ().get ();
	}
	
	
	public BC getBatchCollector () {
		return batchCollector;
	}

	public void setBatchCollector ( BC batchCollector ) {
		this.batchCollector = batchCollector;
	}

	
	/**
	 * Every new parallel task that is generated by {@link #process(Object)} and {@link #handleNewBatch(Object, boolean)}
	 * runs this batchJob.
	 */
	public BJ getBatchJob () {
		return batchJob;
	}

	public void setBatchJob ( BJ batchJob ) {
		this.batchJob = batchJob;
	}


	/**
	 * <p>The factory for the thread pool manager used by {@link #process(Object, Object...)}.</p>
	 * 
	 * <p>By default this is {@link HackedBlockingQueue#createExecutor()}. Normally you shouldn't need to 
	 * change this parameter, unless you want some particular execution policy.</p>
	 * 
	 */
	public Supplier<ExecutorService> getExecutorFactory () {
		return executorFactory;
	}

	public void setExecutorFactory ( Supplier<ExecutorService> executorFactory ) {
		this.executorFactory = executorFactory;
	}
	
	/**
	 * This is initialised using the first time you call it, by means of {@link #getExecutorFactory()}. If you 
	 * invoke {@link #waitExecutor(String)}, in {@link #process(Object, Object...)}, as recommended, the internal 
	 * {@link ExecutorService} returned by this is made to null again and then re-initialised by this method upon its
	 * next call.
	 */
	public ExecutorService getExecutor ()
	{
		if ( executor == null ) executor = this.executorFactory.get ();
		return executor;
	}

	/**
	 * <p>Waits that all the parallel jobs submitted to the batchJob are finished. It keeps polling
	 * {@link ExecutorService#isTerminated()} and invoking {@link ExecutorService#awaitTermination(long, TimeUnit)}.</p>
	 * 
	 * <p>As explained above, this resets the {@link ExecutorService} that is returned by {@link #getExecutor()}, so that
	 * the next time that method is invoked, it will get a new executor from {@link #getExecutorFactory()}.</p>
	 * 
	 * @param pleaseWaitMessage the message to be reported (via logger/INFO level) while waiting.
	 */
	protected void waitExecutor ( String pleaseWaitMessage )
	{
		ExecutorService executor = getExecutor ();
		executor.shutdown (); 

		// Wait to finish
		try
		{
			while ( !executor.isTerminated () ) 
			{
				log.info ( pleaseWaitMessage ); 
				executor.awaitTermination ( 5, TimeUnit.MINUTES );
			}
		}
		catch ( InterruptedException ex ) {
			throw new UnexpectedEventException ( 
				"Unexpected interruption while waiting for batchJob termination: " + ex.getMessage (), ex 
			);
		}
		
		this.executor = null;
	}
	
	/**
	 * Wraps the task into some common operations. At the moment,
	 * 
	 *   * wraps exception,
	 *   * logs the progress of completed tasks every {@link #jobLogPeriod} completed tasks.
	 *   
	 */
	protected Runnable wrapBatchJob ( Runnable batchJob )
	{
		return () -> 
		{
			try {
				batchJob.run ();
			}
			catch ( Exception ex )
			{
				log.error ( 
					String.format ( 
						"Error while running batch batchJob thread %s: %s", 
						Thread.currentThread ().getName (), ex.getMessage () 
					),
					ex
				);
			}
			finally {
				long completed = this.completedBatches.incrementAndGet ();
				long submitted = this.submittedBatches.get ();
				if ( completed % this.jobLogPeriod == 0 
						 || completed == submitted
						 && this.getExecutor ().isShutdown () )
					log.info ( "{}/{} batch jobs completed", completed, submitted );
			}
		};
	}


	public long getSubmittedBatches ()
	{
		return submittedBatches.get ();
	}


	public long getCompletedBatches ()
	{
		return completedBatches.get ();
	}
}
