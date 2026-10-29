package com.cropdoc.app.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.cropdoc.app.data.local.dao.ScanDao;
import com.cropdoc.app.data.local.entity.ScanEntity;
import com.cropdoc.app.data.remote.FirebaseAuthSource;
import com.cropdoc.app.data.remote.FirestoreSyncSource;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ScanRepository offline Room persistence, user-scoped sync, and DPDP deletion.
 */
public final class ScanRepositoryTest {

    private ScanDao mockScanDao;
    private FirestoreSyncSource mockFirestore;
    private ScanRepository repository;

    @Before
    public void setUp() {
        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        mockScanDao = mock(ScanDao.class);
        mockFirestore = mock(FirestoreSyncSource.class);
        repository = new ScanRepository(mockContext, mockScanDao, mockFirestore);
    }

    @Test
    public void saveScanSync_persistsUnsyncedRecordToRoom() {
        DiagnosisResult diag = new DiagnosisResult(
                "tomato_early_blight",
                "tomato",
                0.88f,
                0.21f,
                0.68f,
                26L,
                "1.0.0-int8",
                DiagnosisOutcome.CONFIDENT_DISEASE);
        ScanRecord saved = repository.saveScanSync("farmer_1", diag, "/tmp/leaf.jpg");
        assertEquals("tomato_early_blight", saved.getDiseaseId());
        assertFalse(saved.isSynced());
        verify(mockScanDao).insertOrReplace(any(ScanEntity.class));
    }

    @Test
    public void syncPendingBatch_marksEntitiesSyncedOnFirestoreSuccess() {
        ScanEntity pending = new ScanEntity(
                "scan_101",
                "farmer_1",
                "rice",
                "rice_leaf_blast",
                0.91f,
                "CONFIDENT_DISEASE",
                "",
                null,
                1700000000L,
                false,
                true,
                "1.0.0-int8");
        when(mockScanDao.findUnsyncedBatch("farmer_1", 10))
                .thenReturn(Collections.singletonList(pending));
        when(mockFirestore.uploadScanBatch(eq("farmer_1"), anyList())).thenReturn(true);
        boolean ok = repository.syncPendingBatch("farmer_1");
        assertTrue(ok);
        verify(mockScanDao).markSynced("scan_101", null);
    }

    @Test
    public void syncPendingBatch_guestUser_skipsCloudUpload() {
        boolean ok = repository.syncPendingBatch(FirebaseAuthSource.LOCAL_GUEST_UID);
        assertTrue(ok);
        verify(mockFirestore, never()).uploadScanBatch(any(), anyList());
    }

    @Test
    public void deleteAllUserScansSync_deletesLocalRoomAndCloudFirestore() {
        when(mockScanDao.findAllForUserSync("farmer_1")).thenReturn(Collections.emptyList());
        repository.deleteAllUserScansSync("farmer_1");
        verify(mockScanDao).deleteAllForUser("farmer_1");
        verify(mockFirestore).deleteAllUserCloudData("farmer_1");
    }
}
