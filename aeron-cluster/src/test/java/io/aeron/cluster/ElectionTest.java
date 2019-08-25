/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.Counter;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusterMarkFile;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

@SuppressWarnings("MethodLength")
public class ElectionTest
{
    public static final long RECORDING_ID = 1L;
    private final Aeron aeron = mock(Aeron.class);
    private final Counter electionStateCounter = mock(Counter.class);
    private final RecordingLog recordingLog = mock(RecordingLog.class);
    private final ClusterMarkFile clusterMarkFile = mock(ClusterMarkFile.class);
    private final MemberStatusAdapter memberStatusAdapter = mock(MemberStatusAdapter.class);
    private final MemberStatusPublisher memberStatusPublisher = mock(MemberStatusPublisher.class);
    private final ConsensusModuleAgent consensusModuleAgent = mock(ConsensusModuleAgent.class);

    private final ConsensusModule.Context ctx = new ConsensusModule.Context()
        .aeron(aeron)
        .recordingLog(recordingLog)
        .clusterClock(new TestClusterClock(TimeUnit.MILLISECONDS))
        .random(new Random())
        .clusterMarkFile(clusterMarkFile);

    @Before
    public void before()
    {
        when(aeron.addCounter(anyInt(), anyString())).thenReturn(electionStateCounter);
        when(consensusModuleAgent.logRecordingId()).thenReturn(RECORDING_ID);
        when(consensusModuleAgent.addNewLogPublication()).thenReturn(mock(Publication.class));
        when(clusterMarkFile.candidateTermId()).thenReturn((long)Aeron.NULL_VALUE);
    }

    @Test
    public void shouldElectSingleNodeClusterLeader()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = ClusterMember.parse(
            "0,clientEndpoint,memberEndpoint,logEndpoint,transferEndpoint,archiveEndpoint");

        final ClusterMember thisMember = clusterMembers[0];
        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, thisMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long newLeadershipTermId = leadershipTermId + 1;
        final long t1 = 1;
        election.doWork(t1);
        election.doWork(t1);
        verify(consensusModuleAgent).becomeLeader(eq(newLeadershipTermId), eq(logPosition), anyInt());
        verify(recordingLog).appendTerm(RECORDING_ID, newLeadershipTermId, logPosition, NANOSECONDS.toMillis(t1));
        assertThat(election.state(), is(Election.State.LEADER_READY));
    }

    @Test
    public void shouldElectAppointedLeader()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember candidateMember = clusterMembers[0];

        ctx.appointedLeaderId(candidateMember.id());

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, candidateMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long candidateTermId = leadershipTermId + 1;
        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 1);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t2 = 2;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t3 = t2 + (ctx.electionTimeoutNs() >> 1);
        election.doWork(t3);
        election.doWork(t3);
        verify(memberStatusPublisher).requestVote(
            clusterMembers[1].publication(),
            leadershipTermId,
            logPosition,
            candidateTermId,
            candidateMember.id());
        verify(memberStatusPublisher).requestVote(
            clusterMembers[2].publication(),
            leadershipTermId,
            logPosition,
            candidateTermId,
            candidateMember.id());
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));
        verify(consensusModuleAgent).role(Cluster.Role.CANDIDATE);

        when(consensusModuleAgent.role()).thenReturn(Cluster.Role.CANDIDATE);
        election.onVote(
            candidateTermId, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[1].id(), true);
        election.onVote(
            candidateTermId, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[2].id(), true);

        final long t4 = t3 + 1;
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.LEADER_REPLAY));

        final long t5 = t4 + 1;
        election.doWork(t5);
        election.doWork(t5);
        verify(consensusModuleAgent).becomeLeader(eq(candidateTermId), eq(logPosition), anyInt());
        verify(recordingLog).appendTerm(RECORDING_ID, candidateTermId, logPosition, NANOSECONDS.toMillis(t5));

        assertThat(clusterMembers[1].logPosition(), is(NULL_POSITION));
        assertThat(clusterMembers[2].logPosition(), is(NULL_POSITION));
        assertThat(election.state(), is(Election.State.LEADER_READY));
        assertThat(election.leadershipTermId(), is(candidateTermId));

        final long t6 = t5 + ctx.leaderHeartbeatIntervalNs();
        when(recordingLog.getTermTimestamp(candidateTermId)).thenReturn(t6);
        final int logSessionId = -7;
        election.logSessionId(logSessionId);
        election.doWork(t6);
        verify(memberStatusPublisher).newLeadershipTerm(
            clusterMembers[1].publication(),
            leadershipTermId,
            candidateTermId,
            logPosition,
            t6,
            candidateMember.id(),
            logSessionId);
        verify(memberStatusPublisher).newLeadershipTerm(
            clusterMembers[2].publication(),
            leadershipTermId,
            candidateTermId,
            logPosition,
            t6,
            candidateMember.id(),
            logSessionId);
        assertThat(election.state(), is(Election.State.LEADER_READY));

        when(consensusModuleAgent.electionComplete()).thenReturn(true);

        final long t7 = t6 + 1;
        election.onAppendedPosition(candidateTermId, logPosition, clusterMembers[1].id());
        election.onAppendedPosition(candidateTermId, logPosition, clusterMembers[2].id());
        election.doWork(t7);
        final InOrder inOrder = inOrder(consensusModuleAgent, electionStateCounter);
        inOrder.verify(consensusModuleAgent).electionComplete();
        inOrder.verify(electionStateCounter).close();
    }

    @Test
    public void shouldVoteForAppointedLeader()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final int candidateId = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long candidateTermId = leadershipTermId + 1;
        final long t2 = 2;
        election.onRequestVote(leadershipTermId, logPosition, candidateTermId, candidateId);
        verify(memberStatusPublisher).placeVote(
            clusterMembers[candidateId].publication(),
            candidateTermId,
            leadershipTermId,
            logPosition,
            candidateId,
            followerMember.id(),
            true);
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.FOLLOWER_BALLOT));

        final int logSessionId = -7;
        election.onNewLeadershipTerm(leadershipTermId, candidateTermId, logPosition, t2, candidateId, logSessionId);
        assertThat(election.state(), is(Election.State.FOLLOWER_REPLAY));

        when(consensusModuleAgent.createAndRecordLogSubscriptionAsFollower(anyString()))
            .thenReturn(mock(Subscription.class));
        when(memberStatusPublisher.catchupPosition(any(), anyLong(), anyLong(), anyInt())).thenReturn(Boolean.TRUE);
        when(consensusModuleAgent.hasAppendReachedLivePosition(any(), anyInt(), anyLong())).thenReturn(Boolean.TRUE);
        when(consensusModuleAgent.hasAppendReachedPosition(any(), anyInt(), anyLong())).thenReturn(Boolean.TRUE);
        when(consensusModuleAgent.logSubscriptionTags()).thenReturn("3,4");
        final long t3 = 3;
        election.doWork(t3);
        election.doWork(t3);
        election.doWork(t3);
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.FOLLOWER_READY));

        when(memberStatusPublisher.appendedPosition(any(), anyLong(), anyLong(), anyInt())).thenReturn(Boolean.TRUE);
        when(consensusModuleAgent.electionComplete()).thenReturn(true);

        final long t4 = 4;
        election.doWork(t4);
        final InOrder inOrder = inOrder(memberStatusPublisher, consensusModuleAgent, electionStateCounter);
        inOrder.verify(memberStatusPublisher).appendedPosition(
            clusterMembers[candidateId].publication(), candidateTermId, logPosition, followerMember.id());
        inOrder.verify(consensusModuleAgent).electionComplete();
        inOrder.verify(electionStateCounter).close();
    }

    @Test
    public void shouldCanvassMembersInSuccessfulLeadershipBid()
    {
        final long logPosition = 0;
        final long leadershipTermId = Aeron.NULL_VALUE;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long t2 = t1 + ctx.electionStatusIntervalNs();
        election.doWork(t2);
        verify(memberStatusPublisher).canvassPosition(
            clusterMembers[0].publication(), leadershipTermId, logPosition, followerMember.id());
        verify(memberStatusPublisher).canvassPosition(
            clusterMembers[2].publication(), leadershipTermId, logPosition, followerMember.id());
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t3 = t2 + 1;
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.NOMINATE));
    }

    @Test
    public void shouldCanvassMembersInUnSuccessfulLeadershipBid()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[0];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long t2 = t1 + ctx.electionStatusIntervalNs();
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId + 1, logPosition, 1);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t3 = t2 + 1;
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANVASS));
    }

    @Test
    public void shouldVoteForCandidateDuringNomination()
    {
        final long logPosition = 0;
        final long leadershipTermId = Aeron.NULL_VALUE;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long t2 = t1 + ctx.electionStatusIntervalNs();
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t3 = t2 + 1;
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t4 = t3 + 1;
        final long candidateTermId = leadershipTermId + 1;
        election.onRequestVote(leadershipTermId, logPosition, candidateTermId, 0);
        election.doWork(t4);
        assertThat(election.state(), is(Election.State.FOLLOWER_BALLOT));
    }

    @Test
    public void shouldTimeoutCanvassWithMajority()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onAppendedPosition(leadershipTermId, logPosition, 0);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long t2 = t1 + 1;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long t3 = t2 + ctx.startupCanvassTimeoutNs();
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.NOMINATE));
    }

    @Test
    public void shouldWinCandidateBallotWithMajority()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember candidateMember = clusterMembers[1];

        final Election election = newElection(false, leadershipTermId, logPosition, clusterMembers, candidateMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t2 = t1 + 1;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t3 = t2 + (ctx.electionTimeoutNs() >> 1);
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t4 = t3 + ctx.electionTimeoutNs();
        final long candidateTermId = leadershipTermId + 1;
        when(consensusModuleAgent.role()).thenReturn(Cluster.Role.CANDIDATE);
        election.onVote(
            candidateTermId, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[2].id(), true);
        election.doWork(t4);
        assertThat(election.state(), is(Election.State.LEADER_REPLAY));
    }

    @Test
    public void shouldElectCandidateWithFullVote()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember candidateMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, candidateMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t2 = t1 + 1;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t3 = t2 + (ctx.electionTimeoutNs() >> 1);
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t4 = t3 + 1;
        final long candidateTermId = leadershipTermId + 1;
        when(consensusModuleAgent.role()).thenReturn(Cluster.Role.CANDIDATE);
        election.onVote(
            candidateTermId, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[0].id(), false);
        election.onVote(
            candidateTermId, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[2].id(), true);
        election.doWork(t4);
        assertThat(election.state(), is(Election.State.LEADER_REPLAY));
    }

    @Test
    public void shouldTimeoutCandidateBallotWithoutMajority()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember candidateMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, candidateMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);
        election.onCanvassPosition(leadershipTermId, logPosition, 2);

        final long t2 = t1 + 1;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t3 = t2 + (ctx.electionTimeoutNs() >> 1);
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t4 = t3 + ctx.electionTimeoutNs();
        election.doWork(t4);
        assertThat(election.state(), is(Election.State.CANVASS));
        assertThat(election.leadershipTermId(), is(leadershipTermId));
        assertThat(election.candidateTermId(), is(leadershipTermId + 1));
    }

    @Test
    public void shouldTimeoutFailedCandidateBallotOnSplitVoteThenSucceedOnRetry()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember candidateMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, candidateMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);

        final long t2 = t1 + ctx.startupCanvassTimeoutNs();
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t3 = t2 + (ctx.electionTimeoutNs() >> 1);
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t4 = t3 + 1;
        when(consensusModuleAgent.role()).thenReturn(Cluster.Role.CANDIDATE);
        election.onVote(
            leadershipTermId + 1, leadershipTermId, logPosition, candidateMember.id(), clusterMembers[2].id(), false);
        election.doWork(t4);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t5 = t4 + ctx.electionTimeoutNs();
        election.doWork(t5);
        assertThat(election.state(), is(Election.State.CANVASS));

        election.onCanvassPosition(leadershipTermId, logPosition, 0);

        final long t6 = t5 + 1;
        election.doWork(t6);

        final long t7 = t6 + ctx.electionTimeoutNs();
        election.doWork(t7);
        assertThat(election.state(), is(Election.State.NOMINATE));

        final long t8 = t7 + ctx.electionTimeoutNs();
        election.doWork(t8);
        assertThat(election.state(), is(Election.State.CANDIDATE_BALLOT));

        final long t9 = t8 + 1;
        election.doWork(t9);

        final long candidateTermId = leadershipTermId + 2;
        election.onVote(
            candidateTermId, leadershipTermId + 1, logPosition, candidateMember.id(), clusterMembers[2].id(), true);

        final long t10 = t9 + ctx.electionTimeoutNs();
        election.doWork(t10);
        assertThat(election.state(), is(Election.State.LEADER_REPLAY));

        final long t11 = t10 + 1;
        election.doWork(t11);
        election.doWork(t11);
        assertThat(election.state(), is(Election.State.LEADER_READY));
        assertThat(election.leadershipTermId(), is(candidateTermId));
    }

    @Test
    public void shouldTimeoutFollowerBallotWithoutLeaderEmerging()
    {
        final long leadershipTermId = Aeron.NULL_VALUE;
        final long logPosition = 0L;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember followerMember = clusterMembers[1];

        final Election election = newElection(leadershipTermId, logPosition, clusterMembers, followerMember);

        assertThat(election.state(), is(Election.State.INIT));

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));

        final long candidateTermId = leadershipTermId + 1;
        election.onRequestVote(leadershipTermId, logPosition, candidateTermId, 0);

        final long t2 = t1 + 1;
        election.doWork(t2);
        assertThat(election.state(), is(Election.State.FOLLOWER_BALLOT));

        final long t3 = t2 + ctx.electionTimeoutNs();
        election.doWork(t3);
        assertThat(election.state(), is(Election.State.CANVASS));
        assertThat(election.leadershipTermId(), is(leadershipTermId));
    }

    @Test
    public void shouldBecomeFollowerIfEnteringNewElection()
    {
        final long leadershipTermId = 1;
        final long logPosition = 120;
        final ClusterMember[] clusterMembers = prepareClusterMembers();
        final ClusterMember thisMember = clusterMembers[0];

        when(consensusModuleAgent.role()).thenReturn(Cluster.Role.LEADER);
        final Election election = newElection(false, leadershipTermId, logPosition, clusterMembers, thisMember);

        final long t1 = 1;
        election.doWork(t1);
        assertThat(election.state(), is(Election.State.CANVASS));
        verify(consensusModuleAgent).prepareForNewLeadership(logPosition);
        verify(consensusModuleAgent).role(Cluster.Role.FOLLOWER);
    }

    private Election newElection(
        final boolean isStartup,
        final long logLeadershipTermId,
        final long logPosition,
        final ClusterMember[] clusterMembers,
        final ClusterMember thisMember)
    {
        final Int2ObjectHashMap<ClusterMember> idToClusterMemberMap = new Int2ObjectHashMap<>();

        ClusterMember.addClusterMemberIds(clusterMembers, idToClusterMemberMap);

        return new Election(
            isStartup,
            logLeadershipTermId,
            logPosition,
            clusterMembers,
            idToClusterMemberMap,
            thisMember,
            memberStatusAdapter,
            memberStatusPublisher,
            ctx,
            consensusModuleAgent);
    }

    private Election newElection(
        final long logLeadershipTermId,
        final long logPosition,
        final ClusterMember[] clusterMembers,
        final ClusterMember thisMember)
    {
        final Int2ObjectHashMap<ClusterMember> clusterMemberByIdMap = new Int2ObjectHashMap<>();

        ClusterMember.addClusterMemberIds(clusterMembers, clusterMemberByIdMap);

        return new Election(
            true,
            logLeadershipTermId,
            logPosition,
            clusterMembers,
            clusterMemberByIdMap,
            thisMember,
            memberStatusAdapter,
            memberStatusPublisher,
            ctx,
            consensusModuleAgent);
    }

    private static ClusterMember[] prepareClusterMembers()
    {
        final ClusterMember[] clusterMembers = ClusterMember.parse(
            "0,clientEndpoint,memberEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|" +
            "1,clientEndpoint,memberEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|" +
            "2,clientEndpoint,memberEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|");

        clusterMembers[0].publication(mock(Publication.class));
        clusterMembers[1].publication(mock(Publication.class));
        clusterMembers[2].publication(mock(Publication.class));

        return clusterMembers;
    }
}
