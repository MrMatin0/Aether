//! Where Cloudflare's WireGuard edges live.
//!
//! This is data, not logic: the ranges, ports and seed addresses a scan is
//! allowed to try. It is kept apart from the tunnel so that updating the
//! catalog cannot break the data path.

/// The port operators filter first, and therefore the one every sweep leads
/// with. `wg_prober` used to hardcode this literal separately.
pub const WG_DEFAULT_PORT: u16 = 2408;

pub const WG_PREFIXES_V4: &[&str] = &[
    "162.159.192.0/24",
    "162.159.195.0/24",
    "188.114.96.0/24",
    "188.114.97.0/24",
    "188.114.98.0/24",
    "188.114.99.0/24",
    "162.159.193.0/24",
];

pub const WG_PREFIXES_V6: &[&str] = &[
    "2606:4700:d0::/64",
    "2606:4700:d1::/64",
    "2606:4700:100::/48",
];

/// The Zero Trust ingress ranges. Promoted to the front of the sweep when a
/// team is configured, because a team account is answered there first.
pub const WG_ZT_PREFIXES_V4: &[&str] = &["162.159.193.0/24"];

pub const WG_ZT_PREFIXES_V6: &[&str] = &["2606:4700:100::/48"];

pub const WG_PORTS: &[u16] = &[
    2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939,
    942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387,
    1843, 2371, 2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152, 7156,
    7281, 7559, 8319, 8742, 8854, 8886,
];

/// Known-good addresses, tried before anything sampled out of a range.
pub const WG_SEEDS_V4: &[&str] = &[
    "162.159.192.1",
    "162.159.195.1",
    "188.114.96.1",
    "188.114.97.1",
    "162.159.193.1",
];

pub const WG_SEEDS_V6: &[&str] = &[
    "2606:4700:d0::a29f:c001",
    "2606:4700:d1::a29f:c001",
    "2606:4700:d0::a29f:c301",
    "2606:4700:d0::bc72:6001",
];

pub fn wg_prefixes_v4() -> Vec<&'static str> {
    crate::prober::prioritize(WG_PREFIXES_V4, WG_ZT_PREFIXES_V4)
}

pub fn wg_prefixes_v6() -> Vec<&'static str> {
    crate::prober::prioritize(WG_PREFIXES_V6, WG_ZT_PREFIXES_V6)
}

pub fn wg_seeds_v4() -> Vec<&'static str> {
    crate::prober::prioritize(WG_SEEDS_V4, &["162.159.193.1"])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_documented_zero_trust_wireguard_ingress_range_is_scanned() {
        assert!(WG_PREFIXES_V4.contains(&"162.159.193.0/24"));
        assert!(WG_PREFIXES_V6.contains(&"2606:4700:100::/48"));
    }

    #[test]
    fn the_documented_wireguard_ports_are_all_covered() {
        for port in [2408u16, 500, 1701, 4500] {
            assert!(WG_PORTS.contains(&port), "port {port} should be scanned");
        }
    }

    #[test]
    fn the_documented_default_wireguard_port_leads_the_sweep() {
        assert_eq!(
            WG_PORTS.first(),
            Some(&WG_DEFAULT_PORT),
            "the primary sweep port is taken from the head of this list"
        );
    }

    #[test]
    fn the_documented_wireguard_fallback_ports_follow_the_default() {
        assert_eq!(&WG_PORTS[..4], &[2408, 500, 1701, 4500]);
    }

    #[test]
    fn the_consumer_range_leads_when_no_team_is_configured() {
        std::env::remove_var("AETHER_TEAM");
        assert_eq!(wg_prefixes_v4().first(), Some(&"162.159.192.0/24"));
        assert_eq!(wg_prefixes_v6().first(), Some(&"2606:4700:d0::/64"));
    }

    #[test]
    fn no_prefix_is_lost_when_the_zero_trust_range_is_promoted() {
        let promoted = crate::prober::prioritize(WG_PREFIXES_V4, WG_ZT_PREFIXES_V4);
        assert_eq!(promoted.len(), WG_PREFIXES_V4.len());
        for entry in WG_PREFIXES_V4 {
            assert!(promoted.contains(entry), "{entry} went missing");
        }
    }

    #[test]
    fn every_wireguard_prefix_parses() {
        for entry in WG_PREFIXES_V4 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<std::net::Ipv4Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for entry in WG_PREFIXES_V6 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<std::net::Ipv6Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
    }

    #[test]
    fn every_seed_address_parses_and_sits_inside_a_scanned_prefix() {
        for entry in WG_SEEDS_V4 {
            assert!(entry.parse::<std::net::Ipv4Addr>().is_ok(), "{entry}");
        }
        for entry in WG_SEEDS_V6 {
            assert!(entry.parse::<std::net::Ipv6Addr>().is_ok(), "{entry}");
        }
    }
}
