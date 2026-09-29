package dlp

import (
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Listen              string
	TrustedProxies      []netip.Prefix
	MaxStreams          int
	RouteMaxStreams     int
	IPOpenPerMinute     int
	IPMaxConns          int
	IPMaxHostConns      int
	IdleTimeout         time.Duration
	MaxLifetime         time.Duration
	RouteDailyBytes     int64
	OpenTimeout         time.Duration
	FirstMessageTimeout time.Duration
	WriteTimeout        time.Duration
	Now                 func() time.Time
}

func DefaultConfig() Config {
	return Config{
		Listen:         "127.0.0.1:8411",
		TrustedProxies: []netip.Prefix{netip.MustParsePrefix("127.0.0.1/32"), netip.MustParsePrefix("::1/128")},
		MaxStreams:     2000, RouteMaxStreams: 64, IPOpenPerMinute: 60, IPMaxConns: 64, IPMaxHostConns: 256,
		IdleTimeout: 10 * time.Minute, MaxLifetime: 6 * time.Hour,
		OpenTimeout: 10 * time.Second, FirstMessageTimeout: 5 * time.Second, WriteTimeout: 30 * time.Second,
		Now: time.Now,
	}
}

func LoadConfig() (Config, error) {
	cfg := DefaultConfig()
	if value := os.Getenv("DLP_LISTEN"); value != "" {
		cfg.Listen = value
	}
	var err error
	if cfg.MaxStreams, err = envInt("DLP_MAX_STREAMS", cfg.MaxStreams); err != nil {
		return cfg, err
	}
	if cfg.RouteMaxStreams, err = envInt("DLP_ROUTE_MAX_STREAMS", cfg.RouteMaxStreams); err != nil {
		return cfg, err
	}
	if cfg.IPOpenPerMinute, err = envInt("DLP_IP_OPEN_PER_MIN", cfg.IPOpenPerMinute); err != nil {
		return cfg, err
	}
	if cfg.IPMaxConns, err = envInt("DLP_IP_MAX_CONNS", cfg.IPMaxConns); err != nil {
		return cfg, err
	}
	if cfg.IPMaxHostConns, err = envInt("DLP_IP_MAX_HOST_CONNS", cfg.IPMaxHostConns); err != nil {
		return cfg, err
	}
	if cfg.IdleTimeout, err = envDuration("DLP_IDLE_TIMEOUT", cfg.IdleTimeout); err != nil {
		return cfg, err
	}
	if cfg.MaxLifetime, err = envDuration("DLP_MAX_LIFETIME", cfg.MaxLifetime); err != nil {
		return cfg, err
	}
	if cfg.RouteDailyBytes, err = envInt64("DLP_ROUTE_DAILY_BYTES", 0); err != nil {
		return cfg, err
	}
	if raw := os.Getenv("DLP_TRUSTED_PROXIES"); raw != "" {
		cfg.TrustedProxies = nil
		for _, part := range strings.Split(raw, ",") {
			prefix, parseErr := netip.ParsePrefix(strings.TrimSpace(part))
			if parseErr != nil {
				return cfg, fmt.Errorf("invalid trusted proxy prefix")
			}
			cfg.TrustedProxies = append(cfg.TrustedProxies, prefix.Masked())
		}
	}
	return cfg, cfg.Validate()
}

func (c Config) Validate() error {
	if c.Listen == "" || c.MaxStreams < 1 || c.RouteMaxStreams < 16 || c.RouteMaxStreams > 64 ||
		c.IPOpenPerMinute < 1 || c.IPMaxConns < 16 || c.IPMaxHostConns < 64 || c.IdleTimeout < 2*time.Minute ||
		c.MaxLifetime < 30*time.Minute || c.RouteDailyBytes < 0 {
		return fmt.Errorf("DLP configuration is below protocol limits")
	}
	return nil
}

func envInt(name string, fallback int) (int, error) {
	value := os.Getenv(name)
	if value == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return n, nil
}

func envInt64(name string, fallback int64) (int64, error) {
	value := os.Getenv(name)
	if value == "" {
		return fallback, nil
	}
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return n, nil
}

func envDuration(name string, fallback time.Duration) (time.Duration, error) {
	value := os.Getenv(name)
	if value == "" {
		return fallback, nil
	}
	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return duration, nil
}
