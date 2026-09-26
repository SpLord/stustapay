import type { CustomerPortalApiConfig } from "./generated/api";

const siteHost = window.location.host;
const siteProtocol = window.location.protocol;
const customerApiBaseUrl = `${siteProtocol}//${siteHost}/api`;

export interface Config {
  customerApiBaseUrl: string;
  apiConfig: CustomerPortalApiConfig;
}

export let config: Config;

const generateConfig = (publicApiConfig: CustomerPortalApiConfig): Config => {
  return {
    customerApiBaseUrl: customerApiBaseUrl,
    apiConfig: publicApiConfig,
  };
};

const fetchPublicCustomerApiConfig = async (): Promise<CustomerPortalApiConfig> => {
  const baseUrl = encodeURIComponent(window.location.origin);
  const resp = await fetch(`${customerApiBaseUrl}/config?base_url=${baseUrl}`);
  const respJson = await resp.json();
  // TODO: validation
  return respJson as CustomerPortalApiConfig;
};

export const fetchConfig = async (): Promise<Config> => {
  const publicConfig = await fetchPublicCustomerApiConfig();
  const c = generateConfig(publicConfig);
  config = c;
  return c;
};
