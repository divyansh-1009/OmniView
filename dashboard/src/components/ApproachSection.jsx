import React from 'react';

const ApproachSection = () => {
  return (
    <section className="section" style={{ backgroundColor: 'var(--bg-card)' }}>
      <div className="container">
        <h2>Our Approach</h2>
        <div className="grid grid-cols-2">
          <div>
            <h3>Your Personal Time Machine</h3>
            <p>
              OmniView acts as your seamless, photographic memory. It works silently in the background, continuously observing what you see, and understanding the context of your screen just like you do.
            </p>
            <p>
              When you need to remember something, simply chat with OmniView. Ask it questions like "What was that book my friend recommended last Tuesday?" and it instantly retrieves the exact moment from your digital past. 
            </p>
          </div>
          <div className="card">
            <h4 style={{ marginBottom: '1rem', color: 'var(--text-primary)' }}>How It Feels</h4>
            <ul style={{ listStyle: 'none', padding: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <li><strong>Effortless:</strong> It runs completely automatically. Set it once and never worry about forgetting again.</li>
              <li><strong>Conversational:</strong> Talk to your memory naturally. No keyword searches, just natural questions.</li>
              <li><strong>Instantaneous:</strong> Lightning-fast recall, because everything happens right in the palm of your hand.</li>
              <li><strong>Invisible:</strong> Optimized to protect your battery so you won't even notice it's there until you need it.</li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
};

export default ApproachSection;
