package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft end of the shared-memory link. Skyrim owns the mapping; we open it when it
 * appears and treat it as gone when Skyrim's heartbeat stops. Runs on Windows, and on Linux beside
 * a Skyrim in Proton (through the file Skyrim backs the mapping with).
 */
public final class SkyLink {
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	// Skyrim's loading screens can stall its heartbeat for several seconds.
	private static final long HEARTBEAT_TIMEOUT_MS = 8000;

	/**
	 * Windows (or Minecraft inside Wine, beside Skyrim): open Skyrim's named mapping through kernel32.
	 * Anything else (native Linux beside a Skyrim in Proton): map the file Skyrim backs it with.
	 */
	public static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
	/** The file a Skyrim in Wine backs the mapping with (SKSE plugin: proto::kWineMappingFile). */
	public static final Path LINK_FILE = Path.of(System.getProperty("skycraft.linkFile",
		"/dev/shm/" + MAPPING_NAME.substring(MAPPING_NAME.lastIndexOf('\\') + 1)));

	private static final VarHandle INT = JAVA_INT.varHandle();
	private static final VarHandle LONG = JAVA_LONG.varHandle();

	/** kernel32, loaded only on Windows. */
	private static final class Win32 {
		static final MethodHandle OPEN_FILE_MAPPING;
		// OpenFileMappingW's GetLastError, captured right after the call (the JVM may change it later).
		static final java.lang.foreign.StructLayout CALL_STATE = Linker.Option.captureStateLayout();
		static final VarHandle LAST_ERROR = CALL_STATE.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
		static final MemorySegment OPEN_STATE = Arena.global().allocate(CALL_STATE);
		static final MethodHandle MAP_VIEW_OF_FILE;
		static final MethodHandle GET_TICK_COUNT64;
		static final MethodHandle GET_CURRENT_PROCESS_ID;
		static final MethodHandle QUERY_PERFORMANCE_COUNTER;
		static final MethodHandle QUERY_PERFORMANCE_FREQUENCY;
		static final MethodHandle CREATE_MUTEX;
		static final MemorySegment QPC_OUT = Arena.global().allocate(JAVA_LONG);

		static {
			Linker linker = Linker.nativeLinker();
			SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
			OPEN_FILE_MAPPING = linker.downcallHandle(
				k32.find("OpenFileMappingW").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.captureCallState("GetLastError")
			);
			MAP_VIEW_OF_FILE = linker.downcallHandle(
				k32.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)
			);
			GET_TICK_COUNT64 = linker.downcallHandle(k32.find("GetTickCount64").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
			GET_CURRENT_PROCESS_ID = linker.downcallHandle(k32.find("GetCurrentProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
			QUERY_PERFORMANCE_COUNTER = linker.downcallHandle(k32.find("QueryPerformanceCounter").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
			QUERY_PERFORMANCE_FREQUENCY = linker.downcallHandle(k32.find("QueryPerformanceFrequency").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
			CREATE_MUTEX = linker.downcallHandle(k32.find("CreateMutexW").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
		}
	}

	private static int lastOpenError = -1;
	private static @Nullable Object runningLock;

	/**
	 * Holds a named mutex ("<link name>_minecraft") for as long as this Minecraft runs, so Skyrim's
	 * SKSE plugin knows not to start another one (even before the two have linked up). Off Windows,
	 * a lock on the file "<link file>_minecraft" instead, which Skyrim in Wine checks.
	 */
	public static synchronized void announceRunning() {
		if (runningLock != null) {
			return;
		}
		if (!WINDOWS) {
			try {
				FileChannel channel = FileChannel.open(Path.of(LINK_FILE + "_minecraft"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
				FileLock lock = channel.tryLock();
				runningLock = lock != null ? lock : channel;
			} catch (IOException | RuntimeException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't lock {}_minecraft", LINK_FILE, e);
			}
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(MAPPING_NAME + "_minecraft", StandardCharsets.UTF_16LE);
			runningLock = (MemorySegment) Win32.CREATE_MUTEX.invokeExact(MemorySegment.NULL, 0, name);
		} catch (Throwable t) {
			SkyCraft.LOG.warn("SkyCraft: couldn't create the running-Minecraft mutex", t);
		}
	}

	private static volatile MemorySegment shm;
	private static long lastOpenAttempt;
	private static int skyrimPid;
	private static volatile int generation;
	// active(): Skyrim's heartbeat is on Skyrim's clock, so we only watch it change, on ours.
	private static volatile long skyrimBeatSeen;
	private static volatile long skyrimBeatChangedAt;

	private SkyLink() {
	}

	/** True when a live Skyrim is on the other end. Cheap; safe from any thread. */
	public static boolean active() {
		MemorySegment s = shm;
		if (s == null) {
			return false;
		}
		long beat = (long) LONG.getAcquire(s, OFF_HEADER + H_SKYRIM_HEARTBEAT);
		long now = tickCount();
		if (beat != skyrimBeatSeen) {
			skyrimBeatSeen = beat;
			skyrimBeatChangedAt = now;
		}
		return beat != 0 && now - skyrimBeatChangedAt < HEARTBEAT_TIMEOUT_MS;
	}

	/** Bumps whenever a (new) Skyrim instance is on the other end: everything Skyrim caches must be resent. */
	public static int generation() {
		return generation;
	}

	/**
	 * Process id of the Skyrim we're linked to (0 before the first link). A Windows process id: off
	 * Windows it names a Wine process, not one {@link ProcessHandle} can see.
	 */
	public static int skyrimPid() {
		return skyrimPid;
	}

	/** The mapping if it has been opened (whether or not Skyrim is still alive). */
	public static MemorySegment segment() {
		return shm;
	}

	/** Try to open the mapping at most once a second. Call regularly from the render thread. */
	public static void poll() {
		if (shm != null) {
			LONG.setRelease(shm, OFF_HEADER + H_MC_HEARTBEAT, tickCount());
			int pid = shm.get(JAVA_INT, OFF_HEADER + H_SKYRIM_PID);
			if (pid != skyrimPid) {
				// Skyrim restarted and reset the shared state; start our side over too.
				skyrimPid = pid;
				overlayBack = 1;
				generation++;
				SkyCraft.LOG.info("SkyCraft: Skyrim instance changed (pid {})", pid);
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastOpenAttempt < 1000) {
			return;
		}
		lastOpenAttempt = now;
		MemorySegment seg = WINDOWS ? openWindows() : openFile();
		if (seg == null) {
			return;
		}
		int magic = seg.get(JAVA_INT, OFF_HEADER + H_MAGIC);
		int version = seg.get(JAVA_INT, OFF_HEADER + H_VERSION);
		if (magic != MAGIC || version != VERSION) {
			SkyCraft.LOG.error("SkyCraft: protocol mismatch (magic {} version {}); expected version {}", Integer.toHexString(magic), version, VERSION);
			return;
		}
		seg.set(JAVA_INT, OFF_HEADER + H_MC_PID, currentPid());
		LONG.setRelease(seg, OFF_HEADER + H_MC_HEARTBEAT, tickCount());
		skyrimPid = seg.get(JAVA_INT, OFF_HEADER + H_SKYRIM_PID);
		// Not alive until the heartbeat moves (the mapping may be a closed Skyrim's leftover).
		skyrimBeatSeen = (long) LONG.getAcquire(seg, OFF_HEADER + H_SKYRIM_HEARTBEAT);
		skyrimBeatChangedAt = tickCount() - HEARTBEAT_TIMEOUT_MS;
		generation++;
		shm = seg;
		SkyCraft.LOG.info("SkyCraft: linked to Skyrim (pid {})", seg.get(JAVA_INT, OFF_HEADER + H_SKYRIM_PID));
		if (!WINDOWS) {
			ClockSync.start();
		}
	}

	private static @Nullable MemorySegment openWindows() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(MAPPING_NAME, StandardCharsets.UTF_16LE);
			MemorySegment handle = (MemorySegment) Win32.OPEN_FILE_MAPPING.invokeExact(Win32.OPEN_STATE, FILE_MAP_ALL_ACCESS, 0, name);
			if (handle.address() == 0) {
				// Say why, once per reason: 2 is "Skyrim hasn't made it yet" (normal while it loads),
				// 5 is "not allowed" (a Skyrim run as administrator, before 0.1.1).
				int error = (int) Win32.LAST_ERROR.get(Win32.OPEN_STATE, 0L);
				if (error != lastOpenError) {
					lastOpenError = error;
					SkyCraft.LOG.info("SkyCraft: can't open Skyrim's shared memory yet (Windows error {}{})", error,
						error == 2 ? ": Skyrim hasn't created it yet" : error == 5 ? ": access denied; is Skyrim running as administrator?" : "");
				}
				return null;
			}
			MemorySegment view = (MemorySegment) Win32.MAP_VIEW_OF_FILE.invokeExact(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L);
			if (view.address() == 0) {
				SkyCraft.LOG.error("SkyCraft: MapViewOfFile failed");
				return null;
			}
			return view.reinterpret(MAPPING_BYTES);
		} catch (Throwable t) {
			SkyCraft.LOG.error("SkyCraft: failed to open shared memory", t);
			return null;
		}
	}

	/** The file a Skyrim in Wine backs its mapping with, once it exists at full size. */
	private static @Nullable MemorySegment openFile() {
		try (FileChannel channel = FileChannel.open(LINK_FILE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			if (channel.size() < MAPPING_BYTES) {
				return null;  // Skyrim is still creating it
			}
			// The mapping outlives the channel; it's kept for the life of Minecraft, like the Windows view.
			return channel.map(FileChannel.MapMode.READ_WRITE, 0, MAPPING_BYTES, Arena.global());
		} catch (NoSuchFileException e) {
			if (lastOpenError != 2) {
				lastOpenError = 2;
				SkyCraft.LOG.info("SkyCraft: can't open Skyrim's shared memory yet: {} doesn't exist (Skyrim hasn't created it yet)", LINK_FILE);
			}
			return null;
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.error("SkyCraft: failed to map {}", LINK_FILE, e);
			return null;
		}
	}

	private static int currentPid() {
		if (!WINDOWS) {
			return (int) ProcessHandle.current().pid();
		}
		try {
			return (int) Win32.GET_CURRENT_PROCESS_ID.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Skyrim's QueryPerformanceCounter, so tick timestamps line up across processes. Off Windows
	 * it's our clock shifted by {@link ClockSync}'s offset; 0 until that has a measurement.
	 */
	public static long qpc() {
		if (!WINDOWS) {
			return ClockSync.skyrimQpc();
		}
		synchronized (Win32.QPC_OUT) {
			try {
				int ok = (int) Win32.QUERY_PERFORMANCE_COUNTER.invokeExact(Win32.QPC_OUT);
				return Win32.QPC_OUT.get(JAVA_LONG, 0);
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}
	}

	/** Ticks per second of {@link #qpc()} (0 while unknown). */
	public static long qpcFrequency() {
		if (!WINDOWS) {
			return ClockSync.frequency();
		}
		synchronized (Win32.QPC_OUT) {
			try {
				int ok = (int) Win32.QUERY_PERFORMANCE_FREQUENCY.invokeExact(Win32.QPC_OUT);
				return Win32.QPC_OUT.get(JAVA_LONG, 0);
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}
	}

	/** Milliseconds on this process's own monotonic clock (heartbeats). */
	public static long tickCount() {
		if (!WINDOWS) {
			return System.nanoTime() / 1_000_000L;
		}
		try {
			return (long) Win32.GET_TICK_COUNT64.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Measures Skyrim's QueryPerformanceCounter against our System.nanoTime through the ClockSync
	 * slot: a few round trips every half second, keeping the one with the shortest round trip of
	 * the last few seconds (its midpoint is the most exact).
	 */
	private static final class ClockSync {
		private static final long WINDOW_NS = 4_000_000_000L;
		private static final long REPLY_TIMEOUT_NS = 20_000_000L;
		private static volatile long frequency;
		// skyrimQpc = offsetQpc + nanoTime * frequency / 1e9
		private static volatile double offsetQpc;
		private static volatile boolean measured;
		private static long bestRttNs = Long.MAX_VALUE;
		private static long bestAt;
		private static @Nullable Thread thread;

		static synchronized void start() {
			if (thread != null) {
				return;
			}
			thread = new Thread(ClockSync::run, "SkyCraft clock sync");
			thread.setDaemon(true);
			thread.start();
		}

		static long frequency() {
			return measured ? frequency : 0;
		}

		static long skyrimQpc() {
			if (!measured) {
				return 0;
			}
			return (long) (offsetQpc + System.nanoTime() * (frequency / 1e9));
		}

		private static void run() {
			int request = 0;
			while (true) {
				try {
					MemorySegment s = shm;
					if (s != null && active()) {
						for (int i = 0; i < 4; i++) {
							request++;
							long t1 = System.nanoTime();
							INT.setRelease(s, OFF_CLOCK_SYNC + CS_REQUEST, request);
							while ((int) INT.getAcquire(s, OFF_CLOCK_SYNC + CS_REPLY) != request && System.nanoTime() - t1 < REPLY_TIMEOUT_NS) {
								LockSupport.parkNanos(50_000L);
							}
							long t3 = System.nanoTime();
							if ((int) INT.getAcquire(s, OFF_CLOCK_SYNC + CS_REPLY) != request) {
								continue;  // Skyrim didn't answer in time (an older plugin, or a stall)
							}
							long qpc = (long) LONG.getAcquire(s, OFF_CLOCK_SYNC + CS_QPC);
							long freq = (long) LONG.getAcquire(s, OFF_CLOCK_SYNC + CS_QPC_FREQUENCY);
							if (freq <= 0) {
								continue;
							}
							long rtt = t3 - t1;
							// A short round trip is always better; a stale best is replaced (both clocks drift).
							if (rtt <= bestRttNs || t3 - bestAt > WINDOW_NS) {
								bestRttNs = rtt;
								bestAt = t3;
								long mid = t1 + rtt / 2;
								frequency = freq;
								offsetQpc = qpc - mid * (freq / 1e9);
								if (!measured) {
									measured = true;
									SkyCraft.LOG.info("SkyCraft: synced to Skyrim's clock (round trip {} us)", rtt / 1000);
								}
							}
						}
					}
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				} catch (RuntimeException e) {
					SkyCraft.LOG.warn("SkyCraft: clock sync failed", e);
				}
			}
		}
	}

	// ---- SkyState (read) -------------------------------------------------------------------

	/** Plain snapshot of SkyState. */
	public static final class SkyState {
		public int seq;
		public int flags;
		public int worldId;
		public int collisionEpoch;
		public double x, y, z;
		public float yaw, pitch;
		public int teleportSeq;
		public int viewportW, viewportH;
		public float gameHour;

		public boolean inGame() {
			return (this.flags & SKY_IN_GAME) != 0;
		}

		public boolean menuOpen() {
			return (this.flags & SKY_MENU_OPEN) != 0;
		}

		public boolean loading() {
			return (this.flags & SKY_LOADING) != 0;
		}
	}

	/** Seqlock read of SkyState into {@code out}. Returns false if the link is down. */
	/** Skyrim's water surface around the player (see WaterGrid in the protocol). */
	public static final class WaterGrid {
		public int originX, originZ, worldId;
		public final int size = WATER_GRID_SIZE;
		public final float[] surface = new float[WATER_GRID_SIZE * WATER_GRID_SIZE];
	}

	/** A consistent copy of the water grid, or null (no link, or Skyrim mid-write). */
	public static WaterGrid readWaterGrid() {
		MemorySegment s = shm;
		if (s == null) {
			return null;
		}
		long b = OFF_WATER_GRID;
		WaterGrid out = new WaterGrid();
		for (int attempt = 0; attempt < 100; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + WG_SEQ);
			if ((seq1 & 1) != 0 || seq1 == 0) {
				Thread.onSpinWait();
				if (seq1 == 0) {
					return null; // Skyrim hasn't written one yet
				}
				continue;
			}
			out.originX = s.get(JAVA_INT, b + WG_ORIGIN_X);
			out.originZ = s.get(JAVA_INT, b + WG_ORIGIN_Z);
			out.worldId = s.get(JAVA_INT, b + WG_WORLD_ID);
			for (int i = 0; i < out.surface.length; i++) {
				out.surface[i] = s.get(JAVA_FLOAT, b + WG_SURFACE + i * 4L);
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + WG_SEQ) == seq1) {
				return out;
			}
		}
		return null;
	}

	public static boolean readSkyState(SkyState out) {
		MemorySegment s = shm;
		if (s == null) {
			return false;
		}
		long b = OFF_SKY_STATE;
		for (int attempt = 0; attempt < 1000; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + SS_SEQ);
			if ((seq1 & 1) != 0) {
				if (attempt > 100) {
					Thread.yield();
				} else {
					Thread.onSpinWait();
				}
				continue;
			}
			out.flags = s.get(JAVA_INT, b + SS_FLAGS);
			out.worldId = s.get(JAVA_INT, b + SS_WORLD_ID);
			out.collisionEpoch = s.get(JAVA_INT, b + SS_COLLISION_EPOCH);
			out.x = s.get(JAVA_DOUBLE, b + SS_POS_X);
			out.y = s.get(JAVA_DOUBLE, b + SS_POS_Y);
			out.z = s.get(JAVA_DOUBLE, b + SS_POS_Z);
			out.yaw = s.get(JAVA_FLOAT, b + SS_YAW);
			out.pitch = s.get(JAVA_FLOAT, b + SS_PITCH);
			out.teleportSeq = s.get(JAVA_INT, b + SS_TELEPORT_SEQ);
			out.viewportW = s.get(JAVA_INT, b + SS_VIEWPORT_W);
			out.viewportH = s.get(JAVA_INT, b + SS_VIEWPORT_H);
			out.gameHour = s.get(JAVA_FLOAT, b + SS_GAME_HOUR);
			VarHandle.loadLoadFence();
			int seq2 = (int) INT.getAcquire(s, b + SS_SEQ);
			if (seq1 == seq2) {
				out.seq = seq1;
				return true;
			}
		}
		return false;
	}

	/** Raw SkyState sequence number; changes once per Skyrim frame. */
	public static int skyStateSeq() {
		MemorySegment s = shm;
		return s == null ? 0 : (int) INT.getAcquire(s, OFF_SKY_STATE + SS_SEQ);
	}

	// ---- McState (write) -------------------------------------------------------------------

	public static final class McState {
		public int flags;
		public double x, y, z;
		public float yaw, pitch;
		public float eyeHeight;
		public float sensitivity;
		public int teleportAck;
		public int guiScale;
		public long frameCounter;
		public float fov;
		public float bobPhase;
		public float bobAmount;
		public double eyeX, eyeY, eyeZ;
		public long tickQpc;
		public double prevX, prevY, prevZ;
		public double curX, curY, curZ;
		public float eyeHeightO, eyeHeightT;
		public float walkDistO, walkDist;
		public float bobO, bob;
		public float tickMs = 50.0F;
		public int cameraMode;
		public float cameraDistance;
	}

	public static void writeMcState(McState st) {
		MemorySegment s = shm;
		if (s == null) {
			return;
		}
		long b = OFF_MC_STATE;
		int seq = s.get(JAVA_INT, b + MS_SEQ);
		INT.setRelease(s, b + MS_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + MS_FLAGS, st.flags);
		s.set(JAVA_DOUBLE, b + MS_X, st.x);
		s.set(JAVA_DOUBLE, b + MS_Y, st.y);
		s.set(JAVA_DOUBLE, b + MS_Z, st.z);
		s.set(JAVA_FLOAT, b + MS_YAW, st.yaw);
		s.set(JAVA_FLOAT, b + MS_PITCH, st.pitch);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT, st.eyeHeight);
		s.set(JAVA_FLOAT, b + MS_SENSITIVITY, st.sensitivity);
		s.set(JAVA_INT, b + MS_TELEPORT_ACK, st.teleportAck);
		s.set(JAVA_INT, b + MS_GUI_SCALE, st.guiScale);
		s.set(JAVA_LONG, b + MS_FRAME_COUNTER, st.frameCounter);
		s.set(JAVA_FLOAT, b + MS_FOV, st.fov);
		s.set(JAVA_FLOAT, b + MS_BOB_PHASE, st.bobPhase);
		s.set(JAVA_FLOAT, b + MS_BOB_AMOUNT, st.bobAmount);
		s.set(JAVA_DOUBLE, b + MS_EYE_X, st.eyeX);
		s.set(JAVA_DOUBLE, b + MS_EYE_Y, st.eyeY);
		s.set(JAVA_DOUBLE, b + MS_EYE_Z, st.eyeZ);
		s.set(JAVA_LONG, b + MS_TICK_QPC, st.tickQpc);
		s.set(JAVA_DOUBLE, b + MS_PREV_X, st.prevX);
		s.set(JAVA_DOUBLE, b + MS_PREV_X + 8, st.prevY);
		s.set(JAVA_DOUBLE, b + MS_PREV_X + 16, st.prevZ);
		s.set(JAVA_DOUBLE, b + MS_CUR_X, st.curX);
		s.set(JAVA_DOUBLE, b + MS_CUR_X + 8, st.curY);
		s.set(JAVA_DOUBLE, b + MS_CUR_X + 16, st.curZ);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT_O, st.eyeHeightO);
		s.set(JAVA_FLOAT, b + MS_EYE_HEIGHT_T, st.eyeHeightT);
		s.set(JAVA_FLOAT, b + MS_WALK_O, st.walkDistO);
		s.set(JAVA_FLOAT, b + MS_WALK, st.walkDist);
		s.set(JAVA_FLOAT, b + MS_BOB_O, st.bobO);
		s.set(JAVA_FLOAT, b + MS_BOB, st.bob);
		s.set(JAVA_FLOAT, b + MS_TICK_MS, st.tickMs);
		s.set(JAVA_INT, b + MS_CAMERA_MODE, st.cameraMode);
		s.set(JAVA_FLOAT, b + MS_CAMERA_DISTANCE, st.cameraDistance);
		INT.setRelease(s, b + MS_SEQ, seq + 2);
	}

	// ---- input ring (consume) --------------------------------------------------------------

	public interface InputSink {
		void accept(int type, int code, int a, int b, int c);
	}

	/** Drains every pending input event. Render thread only. */
	public static void drainInput(InputSink sink) {
		MemorySegment s = shm;
		if (s == null) {
			return;
		}
		long base = OFF_INPUT_RING;
		long head = (long) LONG.getAcquire(s, base + IR_HEAD);
		long tail = s.get(JAVA_LONG, base + IR_TAIL);
		if (head - tail > INPUT_RING_ENTRIES) {
			tail = head - INPUT_RING_ENTRIES; // producer lapped us; drop the oldest
		}
		while (tail < head) {
			long e = base + IR_DATA + (tail & (INPUT_RING_ENTRIES - 1)) * 16L;
			int type = Short.toUnsignedInt(s.get(JAVA_SHORT, e));
			int code = Short.toUnsignedInt(s.get(JAVA_SHORT, e + 2));
			int a = s.get(JAVA_INT, e + 4);
			int b = s.get(JAVA_INT, e + 8);
			int c = s.get(JAVA_INT, e + 12);
			tail++;
			sink.accept(type, code, a, b, c);
		}
		LONG.setRelease(s, base + IR_TAIL, tail);
	}

	// ---- actor table (read) ----------------------------------------------------------------

	/** One nearby Skyrim actor (see ActorRecord in the protocol header). */
	public record Actor(int formId, int flags, float x, float y, float z, float yaw, float width, float height, float healthFrac, int level, String name) {
		public boolean dead() {
			return (this.flags & ACTOR_DEAD) != 0;
		}

		public boolean hostile() {
			return (this.flags & ACTOR_HOSTILE) != 0;
		}
	}

	/** Seqlock read of the actor table. Returns false (leaving {@code out} empty) on a torn read. */
	public static boolean readActors(java.util.List<Actor> out) {
		out.clear();
		MemorySegment s = shm;
		if (s == null) {
			return false;
		}
		long b = OFF_ACTOR_TABLE;
		for (int attempt = 0; attempt < 16; attempt++) {
			int seq1 = (int) INT.getAcquire(s, b + AT_SEQ);
			if ((seq1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(s.get(JAVA_INT, b + AT_COUNT), MAX_ACTORS);
			for (int i = 0; i < count; i++) {
				long r = b + AT_RECORDS + i * ACTOR_RECORD_BYTES;
				out.add(new Actor(
					s.get(JAVA_INT, r), s.get(JAVA_INT, r + 4),
					s.get(JAVA_FLOAT, r + 8), s.get(JAVA_FLOAT, r + 12), s.get(JAVA_FLOAT, r + 16),
					s.get(JAVA_FLOAT, r + 20), s.get(JAVA_FLOAT, r + 24), s.get(JAVA_FLOAT, r + 28),
					s.get(JAVA_FLOAT, r + 32), Short.toUnsignedInt(s.get(JAVA_SHORT, r + 36)), readName(s, r + 40, 24)
				));
			}
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, b + AT_SEQ) == seq1) {
				return true;
			}
			out.clear();
		}
		return false;
	}

	private static String readName(MemorySegment s, long off, int max) {
		byte[] bytes = new byte[max];
		int n = 0;
		while (n < max) {
			byte b = s.get(JAVA_BYTE, off + n);
			if (b == 0) {
				break;
			}
			bytes[n++] = b;
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	// ---- event ring (produce) --------------------------------------------------------------

	/** Queues an event for Skyrim. Safe from any thread. Drops the event if Skyrim is a full ring behind. */
	public static void pushEvent(int type, int formId, float a, float b, float c, float d, int flags) {
		pushEvent(type, formId, a, b, c, d, flags, 0);
	}

	public static synchronized void pushEvent(int type, int formId, float a, float b, float c, float d, int flags, int weapon) {
		MemorySegment s = shm;
		if (s == null) {
			return;
		}
		long base = OFF_EVENT_RING;
		long head = s.get(JAVA_LONG, base + ER_HEAD);
		long tail = (long) LONG.getAcquire(s, base + ER_TAIL);
		if (head - tail >= EVENT_RING_ENTRIES) {
			return;
		}
		long e = base + ER_DATA + (head & (EVENT_RING_ENTRIES - 1)) * EVENT_BYTES;
		s.set(JAVA_INT, e, type);
		s.set(JAVA_INT, e + 4, formId);
		s.set(JAVA_FLOAT, e + 8, a);
		s.set(JAVA_FLOAT, e + 12, b);
		s.set(JAVA_FLOAT, e + 16, c);
		s.set(JAVA_FLOAT, e + 20, d);
		s.set(JAVA_INT, e + 24, flags);
		s.set(JAVA_INT, e + 28, weapon);
		LONG.setRelease(s, base + ER_HEAD, head + 1);
	}

	// ---- world entities (write) ------------------------------------------------------------

	/**
	 * One Minecraft thing for Skyrim to draw (see WorldEntity in the protocol header). {@code uv}
	 * holds up to three atlas rects {u0, v0, u1, v1}: sprite/side, top, bottom.
	 */
	public record WorldEntity(int kind, int id, float x, float y, float z, float yaw, float pitch, float scale, float[] ext, float[] uv, int tint) {
	}

	/** Seqlock write of the world-entity table and block selection. Render thread only. */
	public static void writeWorldEntities(java.util.List<WorldEntity> entities, float[] selection) {
		MemorySegment s = shm;
		if (s == null) {
			return;
		}
		long b = OFF_WORLD_ENTITIES;
		int seq = s.get(JAVA_INT, b + WE_SEQ);
		INT.setRelease(s, b + WE_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		int count = Math.min(entities.size(), MAX_WORLD_ENTITIES);
		s.set(JAVA_INT, b + WE_COUNT, count);
		s.set(JAVA_INT, b + WE_HAS_SELECTION, selection != null ? 1 : 0);
		if (selection != null) {
			for (int i = 0; i < 6; i++) {
				s.set(JAVA_FLOAT, b + WE_SEL_MIN + i * 4L, selection[i]);
			}
		}
		for (int i = 0; i < count; i++) {
			WorldEntity w = entities.get(i);
			long r = b + WE_RECORDS + i * WORLD_ENTITY_BYTES;
			s.set(JAVA_INT, r, w.kind());
			s.set(JAVA_INT, r + 4, w.id());
			s.set(JAVA_FLOAT, r + 8, w.x());
			s.set(JAVA_FLOAT, r + 12, w.y());
			s.set(JAVA_FLOAT, r + 16, w.z());
			s.set(JAVA_FLOAT, r + 20, w.yaw());
			s.set(JAVA_FLOAT, r + 24, w.pitch());
			s.set(JAVA_FLOAT, r + 28, w.scale());
			for (int k = 0; k < 3; k++) {
				s.set(JAVA_FLOAT, r + 32 + k * 4L, w.ext() != null ? w.ext()[k] : 0.0F);
			}
			for (int k = 0; k < 12; k++) {
				s.set(JAVA_FLOAT, r + 44 + k * 4L, w.uv() != null && k < w.uv().length ? w.uv()[k] : 0.0F);
			}
			s.set(JAVA_INT, r + 92, w.tint());
		}
		INT.setRelease(s, b + WE_SEQ, seq + 2);
	}

	// ---- render ring (produce) -------------------------------------------------------------

	/**
	 * Writes one render message ({@code header} bytes then {@code body} bytes) into the render ring,
	 * waiting briefly for space. Single producer. Returns false if it never fit.
	 */
	public static boolean writeRender(int type, java.nio.ByteBuffer header, java.nio.ByteBuffer body) {
		return writeRender(type, header, body, 500);
	}

	/** Like writeRender, but gives up at once if the ring is full (per-frame data that the next frame replaces). */
	public static boolean tryWriteRender(int type, java.nio.ByteBuffer header, java.nio.ByteBuffer body) {
		return writeRender(type, header, body, 1);
	}

	private static synchronized boolean writeRender(int type, java.nio.ByteBuffer header, java.nio.ByteBuffer body, int attempts) {
		MemorySegment s = shm;
		if (s == null) {
			return false;
		}
		int payload = header.remaining() + (body != null ? body.remaining() : 0);
		long msgBytes = (8 + payload + 7) & ~7L;
		if (msgBytes > RR_DATA_BYTES / 2) {
			SkyCraft.LOG.warn("SkyCraft: render message too large ({} bytes)", msgBytes);
			return false;
		}
		long base = OFF_RENDER_RING;
		for (int attempt = 0; attempt < attempts; attempt++) {
			long head = s.get(JAVA_LONG, base + RR_HEAD);
			long tail = (long) LONG.getAcquire(s, base + RR_TAIL);
			long pos = head % RR_DATA_BYTES;
			long pad = pos + msgBytes > RR_DATA_BYTES ? RR_DATA_BYTES - pos : 0;
			if (RR_DATA_BYTES - (head - tail) < msgBytes + pad) {
				if (attempt + 1 >= attempts) {
					break;
				}
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					return false;
				}
				continue;
			}
			if (pad > 0) {
				s.set(JAVA_INT, base + RR_DATA + pos, REN_PAD);
				s.set(JAVA_INT, base + RR_DATA + pos + 4, 0);
				head += pad;
				pos = 0;
			}
			long at = base + RR_DATA + pos;
			s.set(JAVA_INT, at, type);
			s.set(JAVA_INT, at + 4, payload);
			MemorySegment.copy(MemorySegment.ofBuffer(header), 0, s, at + 8, header.remaining());
			if (body != null && body.remaining() > 0) {
				MemorySegment.copy(MemorySegment.ofBuffer(body), 0, s, at + 8 + header.remaining(), body.remaining());
			}
			LONG.setRelease(s, base + RR_HEAD, head + msgBytes);
			return true;
		}
		return false;
	}

	// ---- overlay (publish) -----------------------------------------------------------------

	private static int overlayBack = 1; // writer's private slot; Skyrim's front starts at 2, middle at 0

	/** Returns the byte offset where the next overlay frame should be written. */
	public static long overlayBackSlotOffset() {
		return OFF_OVERLAY_PIXELS + overlayBack * OVERLAY_SLOT_BYTES;
	}

	/** Publishes the frame just written into the back slot. */
	public static void publishOverlay(int width, int height, boolean bottomUp, long frameId) {
		MemorySegment s = shm;
		if (s == null) {
			return;
		}
		long hdr = OFF_OVERLAY_SLOT_HDR + overlayBack * SLOT_HDR_SIZE;
		s.set(JAVA_INT, hdr + SH_WIDTH, width);
		s.set(JAVA_INT, hdr + SH_HEIGHT, height);
		s.set(JAVA_INT, hdr + SH_FLAGS, bottomUp ? 1 : 0);
		s.set(JAVA_LONG, hdr + SH_FRAME_ID, frameId);
		int old = (int) INT.getAndSet(s, OFF_OVERLAY_CTL + OC_STATE, overlayBack | OVERLAY_DIRTY);
		overlayBack = old & 3;
		LONG.getAndAdd(s, OFF_OVERLAY_CTL + OC_FRAMES_PUBLISHED, 1L);
	}

	// ---- collision ring (consume) ----------------------------------------------------------

	public static long collisionHead() {
		MemorySegment s = shm;
		return s == null ? 0 : (long) LONG.getAcquire(s, OFF_COLLISION_RING + CR_HEAD);
	}

	public static long collisionTail() {
		MemorySegment s = shm;
		return s == null ? 0 : s.get(JAVA_LONG, OFF_COLLISION_RING + CR_TAIL);
	}

	public static void setCollisionTail(long tail) {
		MemorySegment s = shm;
		if (s != null) {
			LONG.setRelease(s, OFF_COLLISION_RING + CR_TAIL, tail);
		}
	}
}
