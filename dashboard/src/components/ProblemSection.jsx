import React from 'react';

const ProblemSection = () => {
  return (
    <section id="problem" className="section">
      <div className="container">
        <div style={{ maxWidth: '800px' }}>
          <h2 style={{ color: 'var(--accent-primary)' }}>The Problem</h2>
          <h3>Lost in the Digital Flood</h3>
          <p>
            We live on our phones. Every day, countless brilliant ideas, fleeting conversations, and must-buy product recommendations flash across our screens—only to be lost forever the moment we swipe away. 
          </p>
          <p>
            Trying to find that one specific thing you saw last week is impossible. While other companies offer "AI memory," they demand that you upload your deeply personal life to their cloud servers, stripping away your privacy.
          </p>
        </div>
      </div>
    </section>
  );
};

export default ProblemSection;
