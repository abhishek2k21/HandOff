import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import {
  createSession,
  getSession,
  getSessionEvents,
  getTickets,
  listSessions,
} from '../api/sessions';
import type { Session, Ticket } from '../api/types';
import { SessionDetailPage } from '../pages/SessionDetailPage';
import { SessionsPage } from '../pages/SessionsPage';
import { useAuthStore } from '../store/auth';

vi.mock('../api/sessions', () => ({
  listSessions: vi.fn(),
  getTickets: vi.fn(),
  createSession: vi.fn(),
  getSession: vi.fn(),
  getSessionEvents: vi.fn(),
}));

const mockListSessions = vi.mocked(listSessions);
const mockGetTickets = vi.mocked(getTickets);
const mockCreateSession = vi.mocked(createSession);
const mockGetSession = vi.mocked(getSession);
const mockGetSessionEvents = vi.mocked(getSessionEvents);

const fakeTickets: Ticket[] = [
  {
    id: 'T-101',
    customerName: 'Aarav Patel',
    customerEmail: 'aarav@example.com',
    subject: 'Where is order 8841?',
    description: 'Order placed 3 days ago',
    status: 'OPEN',
    orderId: '8841',
    createdAt: '2026-10-03T10:00:00.000Z',
    updatedAt: '2026-10-03T10:00:00.000Z',
  },
  {
    id: 'T-102',
    customerName: 'Diya Sharma',
    customerEmail: 'diya@example.com',
    subject: 'Refund request for damaged item',
    description: 'Item arrived cracked',
    status: 'OPEN',
    orderId: '8841',
    createdAt: '2026-10-03T10:05:00.000Z',
    updatedAt: '2026-10-03T10:05:00.000Z',
  },
];

const fakeSessions: Session[] = [
  {
    id: 's-1',
    ticketId: 'T-101',
    agentType: 'SCRIPTED',
    scenario: 'SIMPLE_LOOKUP',
    status: 'RUNNING',
    controllerUserId: 'u-1',
    lastSeq: 5,
    stepBudget: 20,
    tokenBudget: 20000,
    createdAt: '2026-10-03T10:15:00.000Z',
    endedAt: null,
  },
  {
    id: 's-2',
    ticketId: 'T-102',
    agentType: 'SCRIPTED',
    scenario: 'LONG_STREAM',
    status: 'COMPLETED',
    controllerUserId: 'u-1',
    lastSeq: 2003,
    stepBudget: 20,
    tokenBudget: 20000,
    createdAt: '2026-10-03T09:00:00.000Z',
    endedAt: '2026-10-03T09:05:00.000Z',
  },
];

describe('Sessions & Scripted Agent Components (Slice 2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'valid-test-access-token',
      user: {
        id: 'u-1',
        name: 'Test Operator',
        role: 'OPERATOR',
        organizationId: 'org-1',
      },
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // 1. The session list renders sessions and the empty state.
  it('1. The session list renders sessions and the empty state', async () => {
    // 1a: With sessions
    mockListSessions.mockResolvedValueOnce(fakeSessions);
    mockGetTickets.mockResolvedValueOnce(fakeTickets);

    const { unmount } = render(
      <SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />
    );

    await waitFor(() => {
      expect(screen.getByText('T-101')).toBeInTheDocument();
      expect(screen.getByText('T-102')).toBeInTheDocument();
      expect(screen.getByText('SIMPLE_LOOKUP')).toBeInTheDocument();
      expect(screen.getByText('LONG_STREAM')).toBeInTheDocument();
      expect(screen.getByLabelText(/status: RUNNING/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/status: COMPLETED/i)).toBeInTheDocument();
    });

    unmount();

    // 1b: Empty state
    mockListSessions.mockResolvedValueOnce([]);
    mockGetTickets.mockResolvedValueOnce([]);

    render(<SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('empty-sessions')).toBeInTheDocument();
      expect(screen.getByText(/no sessions yet/i)).toBeInTheDocument();
    });
  });

  // 2. The new-session form submits the chosen ticket and scenario.
  it('2. The new-session form submits the chosen ticket and scenario', async () => {
    mockListSessions.mockResolvedValueOnce([]);
    mockGetTickets.mockResolvedValueOnce(fakeTickets);
    mockCreateSession.mockResolvedValueOnce({
      ...fakeSessions[0],
      id: 'new-session-id',
      ticketId: 'T-102',
      scenario: 'LONG_STREAM',
    });

    const onSelectSession = vi.fn();
    render(<SessionsPage userRole="OPERATOR" onSelectSession={onSelectSession} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /new session/i })).toBeInTheDocument();
    });

    // Open the new session form
    fireEvent.click(screen.getByRole('button', { name: /new session/i }));

    // Verify form fields
    expect(screen.getByLabelText(/ticket/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/scenario/i)).toBeInTheDocument();

    // Select ticket T-102 and scenario LONG_STREAM
    fireEvent.change(screen.getByLabelText(/ticket/i), {
      target: { value: 'T-102' },
    });
    fireEvent.change(screen.getByLabelText(/scenario/i), {
      target: { value: 'LONG_STREAM' },
    });

    // Submit form
    fireEvent.click(screen.getByRole('button', { name: /start session/i }));

    await waitFor(() => {
      expect(mockCreateSession).toHaveBeenCalledTimes(1);
      expect(mockCreateSession).toHaveBeenCalledWith({
        ticketId: 'T-102',
        agentType: 'SCRIPTED',
        scenario: 'LONG_STREAM',
      });
      expect(onSelectSession).toHaveBeenCalledWith('new-session-id');
    });
  });

  // 3. A 409 error shows a readable message.
  it('3. A 409 error shows a readable message', async () => {
    mockListSessions.mockResolvedValueOnce([]);
    mockGetTickets.mockResolvedValueOnce(fakeTickets);
    mockCreateSession.mockRejectedValueOnce(
      new ApiError(409, 'INVALID_STATE', 'An active session already exists for this ticket.')
    );

    render(<SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /new session/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /new session/i }));
    fireEvent.click(screen.getByRole('button', { name: /start session/i }));

    await waitFor(() => {
      const alert = screen.getByRole('alert');
      expect(alert).toBeInTheDocument();
      expect(alert).toHaveTextContent(/active session already exists/i);
    });
  });

  // 4. Polling requests fromSeq equal to the last seen seq and does not re-add events.
  it('4. Polling requests fromSeq equal to the last seen seq and does not re-add events', async () => {
    vi.useFakeTimers();

    mockGetSession.mockResolvedValue({
      id: 's-1',
      ticketId: 'T-101',
      agentType: 'SCRIPTED',
      scenario: 'SIMPLE_LOOKUP',
      status: 'RUNNING',
      controllerUserId: 'u-1',
      lastSeq: 2,
      stepBudget: 20,
      tokenBudget: 20000,
      createdAt: '2026-10-03T10:15:00.000Z',
      endedAt: null,
    });

    // Initial event fetch returns seq 1 and seq 2
    mockGetSessionEvents.mockResolvedValueOnce({
      sessionId: 's-1',
      events: [
        {
          sessionId: 's-1',
          seq: 1,
          type: 'SESSION_STARTED',
          actor: { kind: 'USER', id: 'u-1' },
          payload: { ticketId: 'T-101', scenario: 'SIMPLE_LOOKUP' },
          createdAt: '2026-10-03T10:15:00.000Z',
        },
        {
          sessionId: 's-1',
          seq: 2,
          type: 'AGENT_TEXT',
          actor: { kind: 'AGENT', id: 'agent-1' },
          payload: { text: 'Initial message' },
          createdAt: '2026-10-03T10:15:01.000Z',
        },
      ],
      lastSeq: 2,
      hasMore: false,
    });

    render(<SessionDetailPage sessionId="s-1" onBack={vi.fn()} />);

    // Flush the initial async useEffect
    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.getByText('Initial message')).toBeInTheDocument();
    expect(mockGetSessionEvents).toHaveBeenCalledWith('s-1', 0, 200);

    // Setup next poll response: returns duplicate seq 2 and new seq 3
    mockGetSessionEvents.mockResolvedValueOnce({
      sessionId: 's-1',
      events: [
        {
          sessionId: 's-1',
          seq: 2,
          type: 'AGENT_TEXT',
          actor: { kind: 'AGENT', id: 'agent-1' },
          payload: { text: 'Initial message' },
          createdAt: '2026-10-03T10:15:01.000Z',
        },
        {
          sessionId: 's-1',
          seq: 3,
          type: 'AGENT_TEXT',
          actor: { kind: 'AGENT', id: 'agent-1' },
          payload: { text: 'Next polling step' },
          createdAt: '2026-10-03T10:15:02.000Z',
        },
      ],
      lastSeq: 3,
      hasMore: false,
    });

    // Advance 1 second to trigger the poll inside act
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    // Verify polling requested fromSeq equal to last seen seq (2)
    expect(mockGetSessionEvents).toHaveBeenLastCalledWith('s-1', 2, 200);

    // Verify seq 3 rendered and seq 2 was not duplicated
    expect(screen.getByText('Next polling step')).toBeInTheDocument();
    expect(screen.getAllByText('Initial message')).toHaveLength(1);
  });

  // 5. Polling stops when the session is terminal.
  it('5. Polling stops when the session is terminal', async () => {
    vi.useFakeTimers();

    mockGetSession.mockResolvedValue({
      id: 's-1',
      ticketId: 'T-101',
      agentType: 'SCRIPTED',
      scenario: 'SIMPLE_LOOKUP',
      status: 'RUNNING',
      controllerUserId: 'u-1',
      lastSeq: 1,
      stepBudget: 20,
      tokenBudget: 20000,
      createdAt: '2026-10-03T10:15:00.000Z',
      endedAt: null,
    });

    mockGetSessionEvents.mockResolvedValueOnce({
      sessionId: 's-1',
      events: [
        {
          sessionId: 's-1',
          seq: 1,
          type: 'SESSION_STARTED',
          actor: { kind: 'USER', id: 'u-1' },
          payload: { ticketId: 'T-101' },
          createdAt: '2026-10-03T10:15:00.000Z',
        },
      ],
      lastSeq: 1,
      hasMore: false,
    });

    render(<SessionDetailPage sessionId="s-1" onBack={vi.fn()} />);
    await act(async () => {
      await Promise.resolve();
    });

    // Poll 1 returns terminal event SESSION_COMPLETED
    mockGetSessionEvents.mockResolvedValueOnce({
      sessionId: 's-1',
      events: [
        {
          sessionId: 's-1',
          seq: 2,
          type: 'SESSION_COMPLETED',
          actor: { kind: 'AGENT', id: 'agent-1' },
          payload: { outcome: 'RESOLVED', summary: 'Ticket handled successfully' },
          createdAt: '2026-10-03T10:15:02.000Z',
        },
      ],
      lastSeq: 2,
      hasMore: false,
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(screen.getByText('SESSION_COMPLETED')).toBeInTheDocument();
    expect(screen.getByText(/ticket handled successfully/i)).toBeInTheDocument();

    const callCountAfterTerminal = mockGetSessionEvents.mock.calls.length;

    // Advance by 3 more seconds
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });

    // Polling must have stopped; no new calls made
    expect(mockGetSessionEvents.mock.calls.length).toBe(callCountAfterTerminal);
  });

  // 6. Polling stops on unmount.
  it('6. Polling stops on unmount', async () => {
    vi.useFakeTimers();

    mockGetSession.mockResolvedValue({
      id: 's-1',
      ticketId: 'T-101',
      agentType: 'SCRIPTED',
      scenario: 'SIMPLE_LOOKUP',
      status: 'RUNNING',
      controllerUserId: 'u-1',
      lastSeq: 1,
      stepBudget: 20,
      tokenBudget: 20000,
      createdAt: '2026-10-03T10:15:00.000Z',
      endedAt: null,
    });

    mockGetSessionEvents.mockResolvedValue({
      sessionId: 's-1',
      events: [],
      lastSeq: 0,
      hasMore: false,
    });

    const { unmount } = render(
      <SessionDetailPage sessionId="s-1" onBack={vi.fn()} />
    );

    await act(async () => {
      await Promise.resolve();
    });

    // Unmount the component
    unmount();

    const callCountAtUnmount = mockGetSessionEvents.mock.calls.length;

    // Advance 5 seconds
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    // No further calls should happen
    expect(mockGetSessionEvents.mock.calls.length).toBe(callCountAtUnmount);
  });

  // 7. A VIEWER does not see the "New session" button.
  it('7. A VIEWER does not see the "New session" button', async () => {
    mockListSessions.mockResolvedValue([]);
    mockGetTickets.mockResolvedValue([]);

    // Render as VIEWER
    const { unmount } = render(
      <SessionsPage userRole="VIEWER" onSelectSession={vi.fn()} />
    );

    await waitFor(() => {
      expect(screen.getByTestId('empty-sessions')).toBeInTheDocument();
    });

    expect(
      screen.queryByRole('button', { name: /new session/i })
    ).not.toBeInTheDocument();

    unmount();

    // Render as OPERATOR to confirm the button appears for non-viewers
    render(<SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />);

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /new session/i })
      ).toBeInTheDocument();
    });
  });

  // 8. Events with unknown types render without crashing.
  it('8. Events with unknown types render without crashing', async () => {
    mockGetSession.mockResolvedValue({
      id: 's-1',
      ticketId: 'T-101',
      agentType: 'SCRIPTED',
      scenario: 'SIMPLE_LOOKUP',
      status: 'RUNNING',
      controllerUserId: null,
      lastSeq: 1,
      stepBudget: 20,
      tokenBudget: 20000,
      createdAt: '2026-10-03T10:15:00.000Z',
      endedAt: null,
    });

    mockGetSessionEvents.mockResolvedValueOnce({
      sessionId: 's-1',
      events: [
        {
          sessionId: 's-1',
          seq: 1,
          type: 'FUTURE_EXPERIMENTAL_EVENT',
          actor: { kind: 'SYSTEM', id: 'system-agent', name: 'Experimental Actor' },
          payload: { featureFlag: 'alpha_beta', testNumber: 42 },
          createdAt: '2026-10-03T10:15:00.000Z',
        },
      ],
      lastSeq: 1,
      hasMore: false,
    });

    render(<SessionDetailPage sessionId="s-1" onBack={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText('FUTURE_EXPERIMENTAL_EVENT')).toBeInTheDocument();
      expect(screen.getByTestId('generic-event-row')).toBeInTheDocument();
      expect(screen.getByText(/alpha_beta/i)).toBeInTheDocument();
      expect(screen.getByText(/testNumber/i)).toBeInTheDocument();
    });
  });

  describe('Restored Session and Auth Flow', () => {
    it('no protected call while status is "loading"', async () => {
      useAuthStore.setState({
        status: 'loading',
        accessToken: null,
        user: null,
      });

      render(<SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />);

      // Shows loading state
      expect(screen.getByText(/loading sessions/i)).toBeInTheDocument();
      // No calls made while status is loading
      expect(mockListSessions).not.toHaveBeenCalled();
      expect(mockGetTickets).not.toHaveBeenCalled();
    });

    it('calls happen after restore', async () => {
      useAuthStore.setState({
        status: 'loading',
        accessToken: null,
        user: null,
      });

      mockListSessions.mockResolvedValueOnce(fakeSessions);
      mockGetTickets.mockResolvedValueOnce(fakeTickets);

      render(<SessionsPage userRole="OPERATOR" onSelectSession={vi.fn()} />);

      // Before restore: no protected calls
      expect(mockListSessions).not.toHaveBeenCalled();
      expect(mockGetTickets).not.toHaveBeenCalled();

      // Session restores with authenticated status and access token
      act(() => {
        useAuthStore.setState({
          status: 'authenticated',
          accessToken: 'restored-access-token',
          user: {
            id: 'u-1',
            name: 'Restored Operator',
            role: 'OPERATOR',
            organizationId: 'org-1',
          },
        });
      });

      // After restore: calls happen and sessions render
      await waitFor(() => {
        expect(mockListSessions).toHaveBeenCalledTimes(1);
        expect(mockGetTickets).toHaveBeenCalledTimes(1);
      });

      await waitFor(() => {
        expect(screen.getByText('T-101')).toBeInTheDocument();
        expect(screen.getByText('T-102')).toBeInTheDocument();
      });
    });
  });
});

