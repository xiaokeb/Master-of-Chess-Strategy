package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.data.*
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongHostEntryAdmissionTest {
    @Test fun deniedCreationDoesNotOpenNativeOrWriteAnyCheckpoint() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        f.store.authorizeError = LanzhouMahjongEntryDeniedException(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED)
        val host = f.host()
        try {
            host.open()
            assertEquals("请先完成兰州麻将十课教程", host.state.value.error)
            assertTrue(f.sessions.isEmpty())
            assertTrue(f.store.attempts.isEmpty())
        } finally { host.closeAndJoin() }
    }

    @Test fun explicitNewIntentCannotSilentlyRestoreAnExistingGame() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        f.store.saved = LanzhouMahjongStoredSession(f.record(), 11)
        val host = LanzhouMahjongGameHost(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 99),
            sessionFactory = { error("New intent must not open Native") }, dispatcher = f.dispatcher)
        try {
            host.open()
            assertEquals("活动对局已变化，请返回菜单重新读取", host.state.value.error)
            assertEquals(11L, f.store.saved!!.updatedAtEpochMillis)
            assertEquals(0, f.store.authorizations)
        } finally { host.closeAndJoin() }
    }

    @Test fun resumeWithoutCheckpointCannotCreateAReplacement() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = LanzhouMahjongGameHost(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { error("Missing resume must not create") }, dispatcher = f.dispatcher,
            openMode = LanzhouMahjongOpenMode.RESUME)
        try {
            host.open()
            assertNull(host.state.value.table)
            assertTrue(f.store.attempts.isEmpty())
            assertEquals(0, f.store.authorizations)
        } finally { host.closeAndJoin() }
    }

    @Test fun restartDenialKeepsTheOldHandleAndExactPersistedGamePlayable() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        try {
            host.open()
            val saved = f.store.saved
            val old = f.sessions.single()
            f.store.authorizeError = LanzhouMahjongEntryDeniedException(LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE)
            host.restart()
            assertEquals(0, old.closeCalls)
            assertSame(saved, f.store.saved)
            assertEquals(1, f.sessions.size)
            assertFalse(host.state.value.table!!.blocked)
            assertEquals("此难度的可信胜场资格尚未接入", host.state.value.table!!.feedback)
            host.apply(HostFakeSession.action)
            assertEquals(1, old.applied)
        } finally { host.closeAndJoin() }
    }

    @Test fun restartReadErrorDoesNotCloseOrReplaceTheCurrentSession() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        try {
            host.open()
            val saved = f.store.saved
            f.store.authorizeError = IOException("private DAO failure")
            host.restart()
            assertEquals(0, f.sessions.single().closeCalls)
            assertSame(saved, f.store.saved)
            assertFalse(host.state.value.toString().contains("private DAO"))
            assertFalse(host.state.value.table!!.blocked)
        } finally { host.closeAndJoin() }
    }

    @Test fun unconfiguredStoreFailsClosedForDirectHostCreation() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val storage = LanzhouMahjongHostStorage(f.gate) { _ -> object : LanzhouMahjongAdmittedSessionStore {
            override suspend fun load(): LanzhouMahjongStoredSession? = null
            override suspend fun save(snapshot: LanzhouMahjongStoredSession) { error("Unconfigured store wrote") }
        } }
        val host = LanzhouMahjongGameHost(storage, LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { error("Unconfigured admission opened Native") }, dispatcher = f.dispatcher)
        try {
            host.open()
            assertEquals("新局资格暂不可用，请返回后重试", host.state.value.error)
        } finally { host.closeAndJoin() }
    }
}
