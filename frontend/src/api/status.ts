export interface PlatformStatus {
  name: string
  status: 'UP'
  runtime: {
    javaVersion: string
    timestamp: string
  }
}

export async function fetchPlatformStatus(): Promise<PlatformStatus> {
  const response = await fetch('/api/v1/health')

  if (!response.ok) {
    throw new Error(`平台状态请求失败（HTTP ${response.status}）`)
  }

  return response.json() as Promise<PlatformStatus>
}
