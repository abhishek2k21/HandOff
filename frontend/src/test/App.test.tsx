import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import App from '../App';

// vi.mock replaces the real module with a fake one.
// This means our test never makes a real HTTP request.
vi.mock('../api/health', () => ({
  fetchHealth: vi.fn(),
}));

// We import the mocked version so we can control what it returns.
import { fetchHealth } from '../api/health';
const mockFetchHealth = vi.mocked(fetchHealth);

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    // Make fetchHealth hang forever (never resolves)
    mockFetchHealth.mockReturnValue(new Promise(() => {}));
    render(<App />);
    expect(screen.getByText(/checking health/i)).toBeInTheDocument();
  });

  it('shows health status when backend is UP', async () => {
    mockFetchHealth.mockResolvedValue({
      status: 'UP',
      database: 'UP',
      redis: 'UP',
    });

    render(<App />);

    // waitFor retries until the assertion passes (or times out).
    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument();
      expect(screen.getByText('Database')).toBeInTheDocument();
      expect(screen.getByText('Redis')).toBeInTheDocument();
    });
  });

  it('shows error state when backend is unreachable', async () => {
    mockFetchHealth.mockRejectedValue(new Error('Failed to fetch'));

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText(/cannot reach the backend/i)).toBeInTheDocument();
    });
  });
});
