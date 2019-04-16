
package com.heliosapm.benchmark.maps;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LongSummaryStatistics;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import javax.management.JMX;
import javax.management.ObjectName;

import org.caffinitas.ohc.CacheSerializer;
import org.caffinitas.ohc.OHCache;
import org.caffinitas.ohc.OHCacheBuilder;
import org.jctools.maps.NonBlockingHashMapLong;

import com.google.common.base.Charsets;
import com.sun.management.ThreadMXBean;

/**
 * <p>Title: MapBenchmark</p>
 * <p>Description:  </p>
 * <p>Author: Whitehead</p>
 * <p><code>com.heliosapm.benchmark.maps.MapBenchmark</code></p>
 */
public class MapBenchmark {
	public static final boolean MUTATE = true;
	public static final int WARMUP_LOOPS = 2;
	public static final int LOOKUP_LOOPS = 10_000;
	public static final int MAP_SIZE = 100_000;
	public static final String[] MAP_VALUES;
	public static final String[] ALT_MAP_VALUES;
	public static final long[] MAP_KEYS;
	public static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors()  * 2;
	public static final ThreadMXBean threadMxBean = threadMxBean();
	
	
	
	public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(THREAD_COUNT, new ThreadFactory() {
		final AtomicInteger serial = new AtomicInteger();
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "benchmark-" + serial.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});
	
	private static ThreadMXBean threadMxBean() {
		try {
			ObjectName on = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
			return JMX.newMXBeanProxy(ManagementFactory.getPlatformMBeanServer(), on, ThreadMXBean.class);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static long getThreadAllocatedMem(long threadId) {
		return threadMxBean.getThreadAllocatedBytes(threadId);
	}
	
	private static long getThreadCPUTime(long threadId) {
		return threadMxBean.getThreadCpuTime(threadId) 
			+ threadMxBean.getThreadUserTime(threadId);
	}

	
	static {
		log("Loading RefData");

		MAP_KEYS = timeGet("Map Keys Gen", () -> LongStream.range(0, MAP_SIZE)
				.toArray());

		MAP_VALUES  = timeGet("Map Values Gen", () -> IntStream.range(0, MAP_SIZE)
			.mapToObj(i -> UUID.randomUUID().toString())
			.toArray(size -> new String[size]));
		
		ALT_MAP_VALUES  = timeGet("Alt Map Values Gen", () -> IntStream.range(0, MAP_SIZE)
				.mapToObj(i -> UUID.randomUUID().toString())
				.toArray(size -> new String[size]));
		
		log("RefDataLoaded");		
		((ThreadPoolExecutor)THREAD_POOL).prestartAllCoreThreads();
	}
	
	
	
	public static void log(Object format, Object ...args) {
		System.out.println(String.format(format.toString(), args));
	}
	
	public static void time(String name, Runnable r) {
		final long start = System.nanoTime();
		r.run();
		final long elapsed = System.nanoTime() - start;
		log("%s Elapsed: %s ns, %s us, %s ms", name, elapsed, TimeUnit.NANOSECONDS.toMicros(elapsed), TimeUnit.NANOSECONDS.toMillis(elapsed));
		
	}
	
	public static <T> T timeGet(String name, Supplier<T> s) {
		final long start = System.nanoTime();
		T t = s.get();
		final long elapsed = System.nanoTime() - start;
		log("%s Elapsed: %s ns, %s us, %s ms", name, elapsed, TimeUnit.NANOSECONDS.toMicros(elapsed), TimeUnit.NANOSECONDS.toMillis(elapsed));
		return t;
		
	}	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		concurrentHashMapTest();
		nonBlockingHashMapLongTest();
		guavaCacheTest();				
		caffeineTest();
		cache2kTest();		
		oHCacheTest();
	}
	
	public static void cache2kTest() {
		log("Initializing Cache2k Benchmark");
		final org.cache2k.Cache<Long, String> cache2k = org.cache2k.Cache2kBuilder.of(Long.class, String.class)
				.entryCapacity(MAP_SIZE)
				.build();
		time("Cache2k Load", () -> IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
			cache2k.put(MAP_KEYS[i], MAP_VALUES[i]);
		}));
		log("Cache2k Memory Size: " + MemoryMeasurer.measureBytes(cache2k));
		log("Starting Cache2k Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(cache2k, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting Cache2k Benchmark");
		bench(cache2k, LOOKUP_LOOPS, true, MUTATE);
		cache2k.clearAndClose();
		System.gc();
	}
	
	public static void caffeineTest() {
		log("Initializing CaffeineCache Benchmark");
		final com.github.benmanes.caffeine.cache.Cache<Long, String> caffeine = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
				.initialCapacity(MAP_SIZE)
				.build();
		time("Caffeine Load", () -> IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
			caffeine.put(MAP_KEYS[i], MAP_VALUES[i]);
		}));
		log("CaffeineCache Memory Size: " + MemoryMeasurer.measureBytes(caffeine));
		log("Starting CaffeineCache Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(caffeine, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting CaffeineCache Benchmark");
		bench(caffeine, LOOKUP_LOOPS, true, MUTATE);
		caffeine.invalidateAll();
		caffeine.cleanUp();
		System.gc();		
	}
	
	public static void guavaCacheTest() {
		log("Initializing GuavaCache Benchmark");
		final com.google.common.cache.Cache<Long, String> guava = com.google.common.cache.CacheBuilder.newBuilder()
			.initialCapacity(MAP_SIZE)
			.build();
		time("Guava Load", () -> IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
			guava.put(MAP_KEYS[i], MAP_VALUES[i]);
		}));
		log("GuavaCache Memory Size: " + MemoryMeasurer.measureBytes(guava));
		log("Starting GuavaCache Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(guava, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting GuavaCache Benchmark");
		bench(guava, LOOKUP_LOOPS, true, MUTATE);
		guava.invalidateAll();
		guava.cleanUp();
		System.gc();
	}
	
	public static void nonBlockingHashMapLongTest() {
		log("Initializing NBHML Benchmark");
		final NonBlockingHashMapLong<String> nbhml = new NonBlockingHashMapLong<>(MAP_SIZE, true);
		time("Load NBHML", () -> IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
			nbhml.put(MAP_KEYS[i], MAP_VALUES[i]);
		}));
		log("NBHML Memory Size: " + MemoryMeasurer.measureBytes(nbhml));
		log("Starting NBHML Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(nbhml, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting NBHML Benchmark");
		bench(nbhml, LOOKUP_LOOPS, true, MUTATE);
		nbhml.clear();
		System.gc();
	}
	
	public static void concurrentHashMapTest() {
		log("Initializing CHM Benchmark");
		final ConcurrentHashMap<Long, String> chm = new ConcurrentHashMap<>(MAP_SIZE);
		time("Load CHM", () ->
			IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
				chm.put(MAP_KEYS[i], MAP_VALUES[i]);
			})
		);
		log("CHM Memory Size: " + MemoryMeasurer.measureBytes(chm));
		log("Starting CHM Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(chm, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting CHM Benchmark");
		bench(chm, LOOKUP_LOOPS, true, MUTATE);
		chm.clear();
		System.gc();
	}
	
	public static void oHCacheTest() {
		final CacheSerializer<Long> longSerializer = new CacheSerializer<Long>() {

			@Override
			public void serialize(Long value, ByteBuffer buf) {
				buf.putLong(value.longValue());
				
			}

			@Override
			public Long deserialize(ByteBuffer buf) {
				return buf.getLong();
			}

			@Override
			public int serializedSize(Long value) {
				return 8;
			}
			
		};
		
		final CacheSerializer<String> stringSerializer = new CacheSerializer<String>() {
	        public void serialize(String s, ByteBuffer buf) {
	            byte[] bytes = s.getBytes(Charsets.UTF_8);
	            buf.put((byte) ((bytes.length >>> 8) & 0xFF));
	            buf.put((byte) ((bytes.length >>> 0) & 0xFF));
	            buf.put(bytes);
	        }

	        public String deserialize(ByteBuffer buf) {
	            int length = (((buf.get() & 0xff) << 8) + ((buf.get() & 0xff) << 0));
	            byte[] bytes = new byte[length];
	            buf.get(bytes);
	            return new String(bytes, Charsets.UTF_8);
	        }

	        public int serializedSize(String s) {
	            return writeUTFLen(s);
	        }
	    };		
		
		log("Initializing OHCache Benchmark");
		final OHCache<Long, String> ohcache = OHCacheBuilder.<Long, String>newBuilder()				
				.capacity(MAP_SIZE)
				.valueSerializer(stringSerializer)
				.keySerializer(longSerializer)
				.build();
		time("Load OHCache", () -> IntStream.range(0, MAP_SIZE).parallel().forEach(i -> {
			ohcache.put(MAP_KEYS[i], MAP_VALUES[i]);
		}));
		log("OHCache Memory Size: " + MemoryMeasurer.measureBytes(ohcache));
		log("Starting OHCache Benchmark Warmup");
		for(int i = 0; i < WARMUP_LOOPS; i++) {
			bench(ohcache, LOOKUP_LOOPS, false, MUTATE);
		}
		System.gc();
		log("Starting OHCache Benchmark");
		bench(ohcache, LOOKUP_LOOPS, true, MUTATE);
		ohcache.clear();
		System.gc();
	}
	
	
	
	public static void bench(final NonBlockingHashMapLong<String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.get(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}					
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("NBHML Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("NBHML Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("NBHML Times:" + timeStats);
		if(logThis) log("NBHML MemAlloc:" + memStats);
		if(logThis) log("NBHML CPU Time (sec):" + totalCpu);
		if(logThis) log("NBHML JitSum:" + jitSum);
	}
	
	public static void bench(final ConcurrentHashMap<Long, String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.get(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("CHM Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("CHM Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("CHM Times:" + timeStats);
		if(logThis) log("CHM MemAlloc:" + memStats);
		if(logThis) log("CHM CPU Time (sec):" + totalCpu);
		if(logThis) log("CHM JitSum:" + jitSum);
	}
	
	public static void bench(final com.google.common.cache.Cache<Long, String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.getIfPresent(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}					
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("GuavaCache Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("GuavaCache Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("GuavaCache Times:" + timeStats);
		if(logThis) log("GuavaCache MemAlloc:" + memStats);
		if(logThis) log("GuavaCache CPU Time (sec):" + totalCpu);
		if(logThis) log("GuavaCache JitSum:" + jitSum);
	}
	
	public static void bench(final com.github.benmanes.caffeine.cache.Cache<Long, String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.getIfPresent(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}					
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("CaffeineCache Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("CaffeineCache Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("CaffeineCache Times:" + timeStats);
		if(logThis) log("CaffeineCache MemAlloc:" + memStats);
		if(logThis) log("CaffeineCache CPU Time (sec):" + totalCpu);
		if(logThis) log("CaffeineCache JitSum:" + jitSum);
	}
	
	public static void bench(final org.cache2k.Cache<Long, String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.get(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}					
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("Cache2k Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("Cache2k Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("Cache2k Times:" + timeStats);
		if(logThis) log("Cache2k MemAlloc:" + memStats);
		if(logThis) log("Cache2k CPU Time (sec):" + totalCpu);
		if(logThis) log("Cache2k JitSum:" + jitSum);
	}
	
	public static void bench(final OHCache<Long, String> map, final int loops, final boolean logThis, final boolean mutate) {		
		final long[] elapsedTimes = new long[THREAD_COUNT];
		final long[] allocatedMem = new long[THREAD_COUNT];
		final long[] cpuTime = new long[THREAD_COUNT];
		final long[] jitAvoiders = new long[THREAD_COUNT];
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(THREAD_COUNT);
		for(int i = 0; i < THREAD_COUNT; i++) {
			final int threadSeq = i;
			THREAD_POOL.execute(() -> {
				try {
					final long threadId = Thread.currentThread().getId();
					try {
						startLatch.await();
					} catch (Exception x) {}
					final long startCpu = getThreadCPUTime(threadId);
					final long startMem = getThreadAllocatedMem(threadId);
					final long startTime = System.currentTimeMillis();
					int jitAvoider = 0;
					for(int x = 0; x < loops; x++) {
						for(int y = 0; y < MAP_SIZE; y++) {
							if (map.get(MAP_KEYS[y]) != null) {
								jitAvoider++;
							}
							if (mutate) {
								map.put(MAP_KEYS[y], y%2==0 ? MAP_VALUES[y] : ALT_MAP_VALUES[y]); 									
							}							
						}
					}					
					elapsedTimes[threadSeq] = System.currentTimeMillis() - startTime;
					allocatedMem[threadSeq] = getThreadAllocatedMem(threadId) - startMem;
					cpuTime[threadSeq] = getThreadCPUTime(threadId) - startCpu;
					jitAvoiders[threadSeq] = jitAvoider;
				} finally {
					completeLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		if(logThis) log("OHCache Waiting for completion...");
		try {
			completeLatch.await();
		} catch (Exception x) {}
		if(logThis) log("OHCache Complete");
		LongSummaryStatistics timeStats = Arrays.stream(elapsedTimes).summaryStatistics();
		LongSummaryStatistics memStats = Arrays.stream(allocatedMem).summaryStatistics();
		long totalCpu = TimeUnit.NANOSECONDS.toSeconds(Arrays.stream(cpuTime).sum());
		long jitSum = Arrays.stream(jitAvoiders).sum();
		if(logThis) log("OHCache Times:" + timeStats);
		if(logThis) log("OHCache MemAlloc:" + memStats);
		if(logThis) log("OHCache CPU Time (sec):" + totalCpu);
		if(logThis) log("OHCache JitSum:" + jitSum);
	}
	
	 static int writeUTFLen(String str) {
	        int strlen = str.length();
	        int utflen = 0;
	        int c;

	        for (int i = 0; i < strlen; i++) {
	            c = str.charAt(i);
	            if ((c >= 0x0001) && (c <= 0x007F))
	                utflen++;
	            else if (c > 0x07FF)
	                utflen += 3;
	            else
	                utflen += 2;
	        }

	        if (utflen > 65535)
	            throw new RuntimeException("encoded string too long: " + utflen + " bytes");

	        return utflen + 2;
	    }
	

}
