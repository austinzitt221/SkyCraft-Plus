#pragma once
#include <functional>
#include <stdint.h>

/*
* For modders: Copy this file into your own project if you wish to use this API
*
* A copy of True Directional Movement's own header, with the virtual-target calls (interface V6) that
* TrueDirectionalMovement-VirtualTargets.patch adds. Keep it in step with that patch.
*/
	namespace TDM_API
{
	constexpr const auto TDMPluginName = "TrueDirectionalMovement";

	using PluginHandle = SKSE::PluginHandle;
	using ActorHandle = RE::ActorHandle;

	// Available True Directional Movement interface versions
	enum class InterfaceVersion : uint8_t
	{
		V1,
		V2,
		V3,
		V4,
		V5,
		V6
	};

	// Error types that may be returned by the True Directional Movement API
	enum class APIResult : uint8_t
	{
		// Your API call was successful
		OK,

		// You tried to release a resource that was not allocated to you
		// Do not attempt to manipulate the requested resource if you receive this response
		NotOwner,

		// True Directional Movement currently must keep control of this resource for proper functionality
		// Do not attempt to manipulate the requested resource if you receive this response
		MustKeep,

		// You have already been given control of this resource
		AlreadyGiven,

		// Another mod has been given control of this resource at the present time
		// Do not attempt to manipulate the requested resource if you receive this response
		AlreadyTaken,

		// You sent a command on a thread that could cause a data race were it to be processed
		// Do not attempt to manipulate the requested resource if you receive this response
		BadThread,
	};

	enum class DirectionalMovementMode : uint8_t
	{
		// Directional movement is disabled, the movement is vanilla
		kDisabled,

		// Directional movement is enabled, but the player is facing the crosshair
		kVanillaStyle,

		// Directional movement is enabled, and the player is facing the direction of movement
		kDirectional,
		
		// Directional movement is enabled, and the player has a target locked
		kTargetLock
	};

	// True Directional Movement's modder interface
	class IVTDM1
	{
	public:
		/// <summary>
		/// Get the thread ID True Directional Movement is running in.
		/// You may compare this with the result of GetCurrentThreadId() to help determine
		/// if you are using the correct thread.
		/// </summary>
		/// <returns>TID</returns>
		[[nodiscard]] virtual unsigned long GetTDMThreadId() const noexcept = 0;

		/// <summary>
		/// Get the current state (enabled / disabled) of directional movement.
		/// </summary>
		/// <returns>The current state (true / false) of directional movement</returns>
		[[nodiscard]] virtual bool GetDirectionalMovementState() const noexcept = 0;

		/// <summary>
		/// Get the current state (enabled / disabled) of target lock.
		/// </summary>
		/// <returns>The current state (true / false) of target lock</returns>
		[[nodiscard]] virtual bool GetTargetLockState() const noexcept = 0;

		/// <summary>
		/// Get the actor handle of the currently locked target.
		/// </summary>
		/// <returns>The actor handle of the currently locked target</returns>
		[[nodiscard]] virtual ActorHandle GetCurrentTarget() const noexcept = 0;

		/// <summary>
		/// Request the plugin to forcibly disable directional movement.
		/// If granted, directional movement will be disabled for the duration of your control.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, MustKeep, AlreadyGiven, AlreadyTaken</returns>
		[[nodiscard]] virtual APIResult RequestDisableDirectionalMovement(PluginHandle a_myPluginHandle) noexcept = 0;

		/// <summary>
		/// Request the plugin to forcibly disable headtracking.
		/// If granted, this mod's headtracking will be disabled for the duration of your control.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, MustKeep, AlreadyGiven, AlreadyTaken</returns>
		[[nodiscard]] virtual APIResult RequestDisableHeadtracking(PluginHandle a_myPluginHandle) noexcept = 0;

		/// <summary>
		/// Returns the current owner of the forced disable of directional movement.
		/// </summary>
		/// <returns>Handle or kPluginHandle_Invalid if no one currently owns the resource</returns>
		virtual PluginHandle GetDisableDirectionalMovementOwner() const noexcept = 0;

		/// <summary>
		/// Returns the current owner of the forced disable of headtracking.
		/// </summary>
		/// <returns>Handle or kPluginHandle_Invalid if no one currently owns the resource</returns>
		virtual PluginHandle GetDisableHeadtrackingOwner() const noexcept = 0;

		/// <summary>
		/// Release your forced disable of directional movement.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, NotOwner</returns>
		virtual APIResult ReleaseDisableDirectionalMovement(PluginHandle a_myPluginHandle) noexcept = 0;

		/// <summary>
		/// Release your forced disable of headtracking.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, NotOwner</returns>
		virtual APIResult ReleaseDisableHeadtracking(PluginHandle a_myPluginHandle) noexcept = 0;
	};

	class IVTDM2 : public IVTDM1
	{
	public:
		/// <summary>
		/// Request the control over the player character's yaw.
		/// If granted, you may use the SetPlayerYaw function and TDM will not adjust the yaw for the duration of your control.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <param name="a_yawRotationSpeedMultiplier">The speed multiplier to use when smoothly rotating to the desired yaw. 0 is instant.</param>
		/// <returns>OK, MustKeep, AlreadyGiven, AlreadyTaken</returns>
		[[nodiscard]] virtual APIResult RequestYawControl(PluginHandle a_myPluginHandle, float a_yawRotationSpeedMultiplier) noexcept = 0;

		/// <summary>
		/// Tries to set the player character's desired yaw. Will only do so if granted control.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <param name="a_desiredYaw">The desired yaw</param>
		/// <returns>OK, NotOwner</returns>
		virtual APIResult SetPlayerYaw(PluginHandle a_myPluginHandle, float a_desiredYaw) noexcept = 0;

		/// <summary>
		/// Release your control over the player character's yaw.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, NotOwner</returns>
		virtual APIResult ReleaseYawControl(PluginHandle a_myPluginHandle) noexcept = 0;
	};

	class IVTDM3 : public IVTDM2
	{
	public:
		/// <summary>
		/// Get the current directional movement mode
		/// </summary>
		/// <returns>The current directional movement mode</returns>
		[[nodiscard]] virtual DirectionalMovementMode GetDirectionalMovementMode() const noexcept = 0;

		/// <summary>
		/// Gets the original movement input vector, unaltered by TDM
		/// </summary>
		/// <returns>The original input vector</returns>
		[[nodiscard]] virtual RE::NiPoint2 GetActualMovementInput() const noexcept = 0;
	};

	class IVTDM4 : public IVTDM3
	{
	public:
		/// <summary>
		/// Check if the target position for the camera is behind the target when target lock is enabled.
		/// </summary>
		/// <returns>true if target lock is enabled AND the target position is behind the camera. False otherwise.</returns>
		[[nodiscard]] virtual bool IsTargetLockBehindTarget() const noexcept = 0;
	};

	class IVTDM5 : public IVTDM4
	{
	public:
		/// <summary>
		/// Request the plugin to forcibly disable target lock reticle.
		/// If granted, target lock reticle will be disabled for the duration of your control.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, MustKeep, AlreadyGiven, AlreadyTaken</returns>
		[[nodiscard]] virtual APIResult RequestDisableTargetLock(PluginHandle a_myPluginHandle) noexcept = 0;

		/// <summary>
		/// Release your forced disable of target lock reticle.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <returns>OK, NotOwner</returns>
		virtual APIResult ReleaseDisableTargetLock(PluginHandle a_myPluginHandle) noexcept = 0;

		/// <summary>
		/// Returns the current owner of the forced disable of target lock reticle.
		/// </summary>
		/// <returns>Handle or kPluginHandle_Invalid if no one currently owns the resource</returns>
		virtual PluginHandle GetDisableTargetLockOwner() const noexcept = 0;
	};

	// A target another mod wants the player to be able to lock on to, though there is no actor behind it (a mob from another game's
	// world, say). The position is the point to look at and to keep the camera on: the middle of the body.
	struct VirtualTarget
	{
		uint32_t id;  // not 0; stays the same for as long as the target exists
		float    x, y, z;  // world position, in Skyrim's units
		float    health;  // 0..1 of its full health, or less than 0 if not known: the bar shown for it while it is locked on to
		char     name[32];  // its name (zero-ended), shown above that bar; empty for none
	};

	class IVTDM6 : public IVTDM5
	{
	public:
		/// <summary>
		/// Replace the whole set of virtual targets. Call it again whenever they move (every frame is fine); a target that is left out of
		/// the call is gone, and if it was locked on to, the lock is dropped. Target lock picks among them and the actors in the game.
		/// </summary>
		/// <param name="a_myPluginHandle">Your assigned plugin handle</param>
		/// <param name="a_targets">The targets (may be null if a_count is 0)</param>
		/// <param name="a_count">How many</param>
		/// <returns>OK</returns>
		virtual APIResult SetVirtualTargets(PluginHandle a_myPluginHandle, const VirtualTarget* a_targets, uint32_t a_count) noexcept = 0;

		/// <summary>
		/// The id of the virtual target that is locked on to now.
		/// </summary>
		/// <returns>The id, or 0 if none</returns>
		[[nodiscard]] virtual uint32_t GetCurrentVirtualTarget() const noexcept = 0;
	};

	typedef void* (*_RequestPluginAPI)(const InterfaceVersion interfaceVersion);

	/// <summary>
	/// Request the True Directional Movement API interface.
	/// Recommended: Send your request during or after SKSEMessagingInterface::kMessage_PostLoad to make sure the dll has already been loaded
	/// </summary>
	/// <param name="a_interfaceVersion">The interface version to request</param>
	/// <returns>The pointer to the API singleton, or nullptr if request failed</returns>
	[[nodiscard]] inline void* RequestPluginAPI(const InterfaceVersion a_interfaceVersion = InterfaceVersion::V5)
	{
		auto pluginHandle = GetModuleHandle("TrueDirectionalMovement.dll");
		if (!pluginHandle) {
			return nullptr;
		}
		_RequestPluginAPI requestAPIFunction = (_RequestPluginAPI)GetProcAddress(pluginHandle, "RequestPluginAPI");
		if (requestAPIFunction) {
			return requestAPIFunction(a_interfaceVersion);
		}
		return nullptr;
	}
}
