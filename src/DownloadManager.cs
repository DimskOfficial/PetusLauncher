using System.Collections.Concurrent;

namespace PetusLauncher;

// Central download/install manager. Downloads run as detached background tasks
// that KEEP RUNNING regardless of what the UI shows — switching tabs or opening
// a dialog never cancels them. Pages subscribe to Changed and render the current
// snapshot. One job per gameId at a time.
static class DownloadManager
{
    public enum Phase { Idle, Queued, Downloading, Installing, Done, Error }

    public class Job
    {
        public string GameId = "";
        public Phase Phase = Phase.Idle;
        public string Status = "";            // localized status line
        public double Fraction;               // 0..1
        public long BytesDone;
        public long BytesTotal;
        public double SpeedBps;               // bytes/sec (smoothed)
        public double EtaSeconds;             // estimated time remaining
        public string? Error;
    }

    static readonly ConcurrentDictionary<string, Job> _jobs = new();

    // Fired (on a threadpool thread) whenever any job's state changes. UI must
    // marshal to the UI thread itself.
    public static event Action<Job>? Changed;

    public static Job? Get(string gameId) => _jobs.TryGetValue(gameId, out var j) ? j : null;
    public static IEnumerable<Job> All => _jobs.Values;
    public static bool IsActive(string gameId) =>
        _jobs.TryGetValue(gameId, out var j) && (j.Phase == Phase.Downloading || j.Phase == Phase.Installing || j.Phase == Phase.Queued);

    static void Emit(Job j) { try { Changed?.Invoke(j); } catch { } }

    // Start (or no-op if already running) the GDPS install/update.
    public static void StartGdps()
    {
        const string id = "petusgdps";
        if (IsActive(id)) return;
        var job = new Job { GameId = id, Phase = Phase.Queued, Status = "В очереди…" };
        _jobs[id] = job;
        Emit(job);

        _ = Task.Run(async () =>
        {
            var speed = new SpeedMeter();
            try
            {
                job.BytesTotal = await Updater.RemoteSizeAsync();
                Emit(job);
                await Updater.EnsureUpToDateAsync((stage, frac) =>
                {
                    switch (stage)
                    {
                        case "check": job.Phase = Phase.Downloading; job.Status = "Проверка обновлений…"; break;
                        case "download":
                            job.Phase = Phase.Downloading; job.Status = "Загрузка…";
                            job.Fraction = frac;
                            speed.Update(frac, out job.SpeedBps, out job.EtaSeconds, job.BytesTotal);
                            job.BytesDone = (long)(job.BytesTotal * frac);
                            break;
                        case "install": job.Phase = Phase.Installing; job.Status = "Распаковка…"; job.Fraction = 1; break;
                        case "uptodate": job.Status = "Актуальная версия"; break;
                        case "ready": break;
                    }
                    Emit(job);
                });
                job.Phase = Phase.Done; job.Status = "Готово"; job.Fraction = 1;
            }
            catch (Exception ex) { job.Phase = Phase.Error; job.Error = ex.Message; job.Status = "Ошибка: " + ex.Message; }
            Emit(job);
        });
    }

    // Smooths instantaneous speed and derives ETA from fraction deltas over time.
    class SpeedMeter
    {
        long _lastTicks; double _lastFrac; double _ema;
        public void Update(double frac, out double bps, out double eta, long total)
        {
            long now = Environment.TickCount64;
            bps = _ema; eta = 0;
            if (_lastTicks != 0)
            {
                double dt = (now - _lastTicks) / 1000.0;
                if (dt > 0.15)
                {
                    double dFrac = Math.Max(0, frac - _lastFrac);
                    double inst = total > 0 ? dFrac * total / dt : 0;
                    _ema = _ema <= 0 ? inst : _ema * 0.7 + inst * 0.3;
                    bps = _ema;
                    if (bps > 0 && total > 0) eta = (1 - frac) * total / bps;
                    _lastTicks = now; _lastFrac = frac;
                }
            }
            else { _lastTicks = now; _lastFrac = frac; }
        }
    }
}
