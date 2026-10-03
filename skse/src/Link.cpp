#include "Link.h"

#include <sddl.h>

namespace skycraft
{
	namespace
	{
		template <class T>
		std::atomic_ref<T> Atomic(T& a_value)
		{
			return std::atomic_ref<T>(a_value);
		}

		constexpr std::uint64_t kMcTimeoutMs = 3000;

		bool Elevated()
		{
			HANDLE token = nullptr;
			if (!::OpenProcessToken(::GetCurrentProcess(), TOKEN_QUERY, &token)) {
				return false;
			}
			TOKEN_ELEVATION elevation{};
			DWORD           size = 0;
			const bool      ok = ::GetTokenInformation(token, TokenElevation, &elevation, sizeof(elevation), &size);
			::CloseHandle(token);
			return ok && elevation.TokenIsElevated;
		}

		// Who may open the shared memory: this Windows user (and the system and administrators), at
		// normal integrity. Said explicitly because a Skyrim run as administrator would otherwise
		// make it administrators-only, and Minecraft (started through the desktop, so never
		// elevated) couldn't open it: it would sit there hidden, never connecting. Free with LocalFree.
		PSECURITY_DESCRIPTOR SharedWithThisUser()
		{
			std::wstring sddl = L"D:P(A;;GA;;;SY)(A;;GA;;;BA)";
			HANDLE       token = nullptr;
			if (::OpenProcessToken(::GetCurrentProcess(), TOKEN_QUERY, &token)) {
				DWORD size = 0;
				::GetTokenInformation(token, TokenUser, nullptr, 0, &size);
				std::vector<std::uint8_t> buffer(size);
				if (size && ::GetTokenInformation(token, TokenUser, buffer.data(), size, &size)) {
					LPWSTR sid = nullptr;
					if (::ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &sid)) {
						sddl += std::wstring(L"(A;;GA;;;") + sid + L")";
						::LocalFree(sid);
					}
				}
				::CloseHandle(token);
			}
			sddl += L"S:(ML;;NW;;;ME)";
			PSECURITY_DESCRIPTOR descriptor = nullptr;
			if (!::ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &descriptor, nullptr)) {
				logger::warn("shared memory: couldn't build its access rules ({}); using the defaults", ::GetLastError());
				return nullptr;
			}
			return descriptor;
		}
	}

	Link& Link::Get()
	{
		static Link link;
		return link;
	}

	bool Link::Create()
	{
		if (base_) {
			return true;
		}
		const auto size = proto::kMappingBytes;
		if (Elevated()) {
			logger::info("Skyrim is running as administrator");
		}
		// Under Wine, back the mapping with a file in /dev/shm so a native Linux Minecraft can map
		// it too. Windows keeps the plain page-file mapping.
		HANDLE backing = INVALID_HANDLE_VALUE;
		if (RunningUnderWine()) {
			backing = ::CreateFileW(proto::kWineMappingFile, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
				nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
			if (backing == INVALID_HANDLE_VALUE) {
				logger::warn("Wine: couldn't open /dev/shm/SkyCraft_v1 ({}); only a Minecraft inside Wine can connect", ::GetLastError());
			} else {
				logger::info("Wine: shared memory is /dev/shm/SkyCraft_v1 (a native Linux Minecraft can connect)");
			}
		}
		SECURITY_ATTRIBUTES access{ sizeof(access), SharedWithThisUser(), FALSE };
		mapping_ = ::CreateFileMappingW(backing, access.lpSecurityDescriptor ? &access : nullptr, PAGE_READWRITE,
			static_cast<DWORD>(size >> 32), static_cast<DWORD>(size & 0xFFFFFFFF), proto::kMappingName);
		const DWORD created = ::GetLastError();
		if (backing != INVALID_HANDLE_VALUE) {
			::CloseHandle(backing);  // the mapping keeps the file open
		}
		if (access.lpSecurityDescriptor) {
			::LocalFree(access.lpSecurityDescriptor);
		}
		if (!mapping_) {
			logger::error("CreateFileMapping failed ({})", created);
			return false;
		}
		const bool existed = created == ERROR_ALREADY_EXISTS;
		base_ = static_cast<std::uint8_t*>(::MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0, 0));
		if (!base_) {
			logger::error("MapViewOfFile failed ({})", ::GetLastError());
			::CloseHandle(mapping_);
			mapping_ = nullptr;
			return false;
		}

		// A stale mapping can survive if Minecraft still has it open from a previous Skyrim run.
		// Reset everything Skyrim owns so rings and the overlay swap start from a known state.
		auto* header = At<proto::Header>(proto::kOffHeader);
		std::memset(base_ + proto::kOffSkyState, 0, sizeof(proto::SkyState));
		std::memset(base_ + proto::kOffOverlayCtl, 0, 0x100);
		std::memset(base_ + proto::kOffInputRing, 0, proto::kInputRingDataOff);
		std::memset(base_ + proto::kOffCollisionRing, 0, proto::kColRingDataOff);
		std::memset(base_ + proto::kOffActorTable, 0, sizeof(proto::ActorTable));
		std::memset(base_ + proto::kOffEventRing, 0, proto::kEventRingDataOff);
		std::memset(base_ + proto::kOffWorldEntities, 0, sizeof(proto::WorldEntities));
		std::memset(base_ + proto::kOffRenderRing, 0, proto::kRenRingDataOff);
		std::memset(base_ + proto::kOffClockSync, 0, sizeof(proto::ClockSync));
		header->version = proto::kVersion;
		header->skyrimPid = ::GetCurrentProcessId();
		header->skyrimHeartbeatMs = ::GetTickCount64();
		header->mcHeartbeatMs = 0;  // a Minecraft still attached will write it again
		Atomic(header->magic).store(proto::kMagic, std::memory_order_release);

		logger::info("shared memory {} ({} MB, {})", "Local\\SkyCraft_v1", size >> 20, existed ? "reused" : "created");
		std::thread(&Link::AnswerClockSync, this).detach();
		return true;
	}

	bool RunningUnderWine()
	{
		static const bool wine = [] {
			const HMODULE ntdll = ::GetModuleHandleW(L"ntdll.dll");
			return ntdll && ::GetProcAddress(ntdll, "wine_get_version") != nullptr;
		}();
		return wine;
	}

	void Link::AnswerClockSync()
	{
		auto*         sync = At<proto::ClockSync>(proto::kOffClockSync);
		LARGE_INTEGER freq;
		::QueryPerformanceFrequency(&freq);
		Atomic(sync->qpcFrequency).store(freq.QuadPart, std::memory_order_relaxed);
		std::uint32_t answered = 0;
		for (;;) {
			const auto request = Atomic(sync->request).load(std::memory_order_acquire);
			if (request != answered) {
				LARGE_INTEGER now;
				::QueryPerformanceCounter(&now);
				Atomic(sync->qpc).store(now.QuadPart, std::memory_order_relaxed);
				Atomic(sync->reply).store(request, std::memory_order_release);
				answered = request;
			}
			::Sleep(1);
		}
	}

	bool Link::McAlive() const
	{
		if (!base_) {
			return false;
		}
		// Minecraft's heartbeat is on its own clock: alive while the value keeps changing.
		auto&      beat = At<proto::Header>(proto::kOffHeader)->mcHeartbeatMs;
		const auto last = Atomic(beat).load(std::memory_order_acquire);
		const auto now = ::GetTickCount64();
		if (last != mcBeatSeen_.load(std::memory_order_relaxed)) {
			mcBeatSeen_.store(last, std::memory_order_relaxed);
			mcBeatChangedAt_.store(now, std::memory_order_relaxed);
		}
		return last != 0 && now - mcBeatChangedAt_.load(std::memory_order_relaxed) < kMcTimeoutMs;
	}

	std::uint32_t Link::McPid() const
	{
		return base_ ? Atomic(At<proto::Header>(proto::kOffHeader)->mcPid).load(std::memory_order_acquire) : 0;
	}

	void Link::Heartbeat()
	{
		if (base_) {
			Atomic(At<proto::Header>(proto::kOffHeader)->skyrimHeartbeatMs).store(::GetTickCount64(), std::memory_order_release);
		}
	}

	void Link::WriteSkyState(const proto::SkyState& a_state)
	{
		if (!base_) {
			return;
		}
		auto* dst = At<proto::SkyState>(proto::kOffSkyState);
		auto  seq = Atomic(dst->seq);
		const auto s = seq.load(std::memory_order_relaxed);
		seq.store(s + 1, std::memory_order_relaxed);
		std::atomic_thread_fence(std::memory_order_release);
		std::memcpy(reinterpret_cast<std::uint8_t*>(dst) + 4, reinterpret_cast<const std::uint8_t*>(&a_state) + 4, sizeof(proto::SkyState) - 4);
		seq.store(s + 2, std::memory_order_release);
	}

	void Link::WriteWaterGrid(const proto::WaterGrid& a_grid)
	{
		if (!base_) {
			return;
		}
		auto* dst = At<proto::WaterGrid>(proto::kOffWaterGrid);
		auto  seq = Atomic(dst->seq);
		const auto s = seq.load(std::memory_order_relaxed);
		seq.store(s + 1, std::memory_order_relaxed);
		std::atomic_thread_fence(std::memory_order_release);
		std::memcpy(reinterpret_cast<std::uint8_t*>(dst) + 4, reinterpret_cast<const std::uint8_t*>(&a_grid) + 4, sizeof(proto::WaterGrid) - 4);
		seq.store(s + 2, std::memory_order_release);
	}

	bool Link::ReadMcState(proto::McState& a_out) const
	{
		if (!base_) {
			return false;
		}
		auto* src = At<proto::McState>(proto::kOffMcState);
		auto  seq = Atomic(src->seq);
		for (int attempt = 0; attempt < 64; ++attempt) {
			const auto s1 = seq.load(std::memory_order_acquire);
			if (s1 & 1) {
				_mm_pause();
				continue;
			}
			std::memcpy(&a_out, src, sizeof(proto::McState));
			std::atomic_thread_fence(std::memory_order_acquire);
			if (seq.load(std::memory_order_relaxed) == s1) {
				return true;
			}
		}
		return false;
	}

	void Link::PushInput(proto::InputType a_type, std::uint16_t a_code, std::int32_t a_a, std::int32_t a_b, std::int32_t a_c)
	{
		if (!base_) {
			return;
		}
		auto* ring = base_ + proto::kOffInputRing;
		auto& headRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kInputRingHeadOff);
		auto& tailRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kInputRingTailOff);
		const auto head = Atomic(headRef).load(std::memory_order_relaxed);
		const auto tail = Atomic(tailRef).load(std::memory_order_acquire);
		if (head - tail >= proto::kInputRingEntries) {
			return;
		}
		auto* entry = reinterpret_cast<proto::InputEvent*>(ring + proto::kInputRingDataOff) + (head & (proto::kInputRingEntries - 1));
		*entry = { static_cast<std::uint16_t>(a_type), a_code, a_a, a_b, a_c };
		Atomic(headRef).store(head + 1, std::memory_order_release);
	}

	bool Link::WriteCollision(proto::ColType a_type, const void* a_payload, std::uint32_t a_bytes)
	{
		if (!base_) {
			return false;
		}
		auto* ring = base_ + proto::kOffCollisionRing;
		auto& headRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kColRingHeadOff);
		auto& tailRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kColRingTailOff);
		auto* data = ring + proto::kColRingDataOff;
		constexpr auto size = proto::kColRingDataBytes;

		const std::uint64_t msgBytes = (sizeof(proto::ColMsgHeader) + a_bytes + 7) & ~7ull;
		if (msgBytes > size / 2) {
			logger::error("collision message too large ({} bytes)", msgBytes);
			return false;
		}
		auto       head = Atomic(headRef).load(std::memory_order_relaxed);
		const auto tail = Atomic(tailRef).load(std::memory_order_acquire);
		auto       pos = head % size;
		const auto padBytes = (pos + msgBytes > size) ? size - pos : 0;
		if (size - (head - tail) < msgBytes + padBytes) {
			return false;
		}
		if (padBytes) {
			*reinterpret_cast<proto::ColMsgHeader*>(data + pos) = { proto::kColPad, 0 };
			head += padBytes;
			pos = 0;
		}
		*reinterpret_cast<proto::ColMsgHeader*>(data + pos) = { a_type, a_bytes };
		std::memcpy(data + pos + sizeof(proto::ColMsgHeader), a_payload, a_bytes);
		Atomic(headRef).store(head + msgBytes, std::memory_order_release);
		return true;
	}

	void Link::WriteActors(const proto::ActorRecord* a_records, std::uint32_t a_count)
	{
		if (!base_) {
			return;
		}
		auto*      table = At<proto::ActorTable>(proto::kOffActorTable);
		auto       seq = Atomic(table->seq);
		const auto s = seq.load(std::memory_order_relaxed);
		seq.store(s + 1, std::memory_order_relaxed);
		std::atomic_thread_fence(std::memory_order_release);
		const auto count = std::min(a_count, proto::kMaxActors);
		table->count = count;
		if (count) {
			std::memcpy(table->actors, a_records, sizeof(proto::ActorRecord) * count);
		}
		seq.store(s + 2, std::memory_order_release);
	}

	bool Link::PopEvent(proto::McEvent& a_out)
	{
		if (!base_) {
			return false;
		}
		auto*      ring = base_ + proto::kOffEventRing;
		auto&      headRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kEventRingHeadOff);
		auto&      tailRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kEventRingTailOff);
		const auto head = Atomic(headRef).load(std::memory_order_acquire);
		auto       tail = Atomic(tailRef).load(std::memory_order_relaxed);
		if (tail >= head) {
			return false;
		}
		if (head - tail > proto::kEventRingEntries) {
			tail = head - proto::kEventRingEntries;
		}
		a_out = reinterpret_cast<const proto::McEvent*>(ring + proto::kEventRingDataOff)[tail & (proto::kEventRingEntries - 1)];
		Atomic(tailRef).store(tail + 1, std::memory_order_release);
		return true;
	}

	bool Link::ReadWorldEntities(proto::WorldEntities& a_out) const
	{
		if (!base_) {
			return false;
		}
		auto* src = At<proto::WorldEntities>(proto::kOffWorldEntities);
		auto  seq = Atomic(src->seq);
		for (int attempt = 0; attempt < 16; ++attempt) {
			const auto s1 = seq.load(std::memory_order_acquire);
			if (s1 & 1) {
				_mm_pause();
				continue;
			}
			const auto count = std::min(src->count, proto::kMaxWorldEntities);
			std::memcpy(&a_out, src, offsetof(proto::WorldEntities, entities) + sizeof(proto::WorldEntity) * count);
			a_out.count = count;
			std::atomic_thread_fence(std::memory_order_acquire);
			if (seq.load(std::memory_order_relaxed) == s1) {
				return true;
			}
		}
		return false;
	}

	void Link::DrainRender(const std::function<void(std::uint32_t, const std::uint8_t*, std::uint32_t)>& a_fn, std::uint64_t a_maxBytes)
	{
		if (!base_) {
			return;
		}
		auto*      ring = base_ + proto::kOffRenderRing;
		auto&      headRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kRenRingHeadOff);
		auto&      tailRef = *reinterpret_cast<std::uint64_t*>(ring + proto::kRenRingTailOff);
		const auto head = Atomic(headRef).load(std::memory_order_acquire);
		auto       tail = Atomic(tailRef).load(std::memory_order_relaxed);
		auto*      data = ring + proto::kRenRingDataOff;
		constexpr auto size = proto::kRenRingDataBytes;
		std::uint64_t  done = 0;
		while (tail < head && done < a_maxBytes) {
			const auto pos = tail % size;
			const auto* hdr = reinterpret_cast<const proto::ColMsgHeader*>(data + pos);
			if (hdr->type == proto::kRenPad) {
				tail += size - pos;
				continue;
			}
			a_fn(hdr->type, data + pos + sizeof(proto::ColMsgHeader), hdr->payloadBytes);
			const auto msgBytes = (sizeof(proto::ColMsgHeader) + hdr->payloadBytes + 7) & ~7ull;
			tail += msgBytes;
			done += msgBytes;
		}
		Atomic(tailRef).store(tail, std::memory_order_release);
	}

	bool Link::AcquireOverlayFrame()
	{
		if (!base_) {
			return false;
		}
		auto& state = At<proto::OverlayCtl>(proto::kOffOverlayCtl)->state;
		if (!(Atomic(state).load(std::memory_order_acquire) & proto::kOverlayDirty)) {
			return false;
		}
		const auto old = Atomic(state).exchange(overlayFront_, std::memory_order_acq_rel);
		overlayFront_ = old & 3;
		return true;
	}

	void Link::ResetOverlay()
	{
		if (!base_) {
			return;
		}
		Atomic(At<proto::OverlayCtl>(proto::kOffOverlayCtl)->state).store(0, std::memory_order_release);
		overlayFront_ = 2;
	}

	const std::uint8_t* Link::FrontPixels() const
	{
		return base_ + proto::kOffOverlayPixels + proto::kOverlaySlotBytes * overlayFront_;
	}

	const proto::OverlaySlotHdr* Link::FrontHeader() const
	{
		return At<proto::OverlaySlotHdr>(proto::kOffOverlaySlotHdr + sizeof(proto::OverlaySlotHdr) * overlayFront_);
	}
}
