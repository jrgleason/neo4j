/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.state;

import org.neo4j.helpers.collection.CloseableVisitor;
import org.neo4j.kernel.impl.api.TransactionQueue;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.api.index.IndexUpdatesValidator;
import org.neo4j.kernel.impl.storageengine.StorageEngine;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;

import static org.neo4j.kernel.impl.api.TransactionApplicationMode.RECOVERY;
import static org.neo4j.kernel.impl.transaction.log.Commitment.NO_COMMITMENT;

public class RecoveryVisitor implements CloseableVisitor<RecoverableTransaction,Exception>
{
    public interface Monitor
    {
        void transactionRecovered( long txId );
    }

    private final TransactionIdStore store;
    private final StorageEngine storageEngine;
    private final IndexUpdatesValidator indexUpdatesValidator;
    private final Monitor monitor;
    private long lastTransactionIdApplied = -1;
    private long lastTransactionChecksum;
    private LogPosition lastTransactionLogPosition;
    private final TransactionQueue queue = new TransactionQueue( 10_000, this::applyQueue );

    public RecoveryVisitor( TransactionIdStore store,
                            StorageEngine storageEngine,
                            Monitor monitor )
    {
        this.store = store;
        this.storageEngine = storageEngine;
        this.indexUpdatesValidator = storageEngine.indexUpdatesValidatorForRecovery();
        this.monitor = monitor;
    }

    @Override
    public boolean visit( RecoverableTransaction transaction ) throws Exception
    {
        CommittedTransactionRepresentation representation = transaction.representation();
        long txId = representation.getCommitEntry().getTxId();
        TransactionRepresentation txRepresentation = representation.getTransactionRepresentation();

        queue( txRepresentation, txId );

        lastTransactionIdApplied = txId;
        lastTransactionChecksum = LogEntryStart.checksum( representation.getStartEntry() );
        lastTransactionLogPosition = transaction.positionAfterTx();
        monitor.transactionRecovered( txId );
        return false;
    }

    private void queue( TransactionRepresentation txRepresentation, long txId ) throws Exception
    {
        TransactionToApply tx = new TransactionToApply( txRepresentation, txId );

        // Recovery operates under a condition that all index updates are valid because otherwise they have no chance to
        // appear in write ahead log.
        // This step is still needed though, because it is not only about validation of index sizes but also about
        // inferring {@link org.neo4j.kernel.api.index.NodePropertyUpdate}s from commands in transaction state and
        // grouping those by {@link org.neo4j.kernel.api.index.IndexUpdater}s.
        tx.validatedIndexUpdates( indexUpdatesValidator.validate( txRepresentation ) );
        tx.commitment( NO_COMMITMENT, txId );

        queue.queue( tx );
    }

    private void applyQueue( TransactionToApply batch ) throws Exception
    {
        storageEngine.apply( batch, RECOVERY );
    }

    @Override
    public void close() throws Exception
    {
        queue.empty();
        if ( lastTransactionIdApplied != -1 )
        {
            store.setLastCommittedAndClosedTransactionId( lastTransactionIdApplied, lastTransactionChecksum,
                    lastTransactionLogPosition.getLogVersion(), lastTransactionLogPosition.getByteOffset() );
        }
    }
}
